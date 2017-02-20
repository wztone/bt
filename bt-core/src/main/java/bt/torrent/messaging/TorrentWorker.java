package bt.torrent.messaging;

import bt.metainfo.TorrentId;
import bt.net.IMessageDispatcher;
import bt.net.Peer;
import bt.protocol.Have;
import bt.protocol.Interested;
import bt.protocol.Message;
import bt.protocol.NotInterested;
import bt.torrent.Bitfield;
import bt.torrent.BitfieldBasedStatistics;
import bt.torrent.selector.PieceSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages peer workers.
 *
 * @since 1.0
 */
class TorrentWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(TorrentWorker.class);

    private static final Duration UPDATE_ASSIGNMENTS_OPTIONAL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration UPDATE_ASSIGNMENTS_MANDATORY_INTERVAL = Duration.ofSeconds(5);
    private static final int MAX_CONCURRENT_ACTIVE_CONNECTIONS = 20;
    private static final int MAX_ASSIGNED_PIECES_PER_PEER = 50;

    private static class AssignmentStatus {

        private static final Duration MAX_PIECE_RECEIVING_TIME = Duration.ofSeconds(30);
        private static final Duration MAX_EXPECTED_BLOCK_RECEIVING_INTERVAL = Duration.ofSeconds(3);

        enum Status { CHOKING, READY, WAITING, ACTIVE, TIMEOUT };

        private Peer peer;
        private ConnectionState connectionState;
        private Assignments assignments;
        private Status status;

        AssignmentStatus(Peer peer, ConnectionState connectionState, Assignments assignments) {
            this.peer = peer;
            this.connectionState = connectionState;
            this.assignments = assignments;
        }

        Status getStatus() {
            status = determineStatus();
            return status;
        }

        private Status determineStatus() {
            if (connectionState.isPeerChoking()) {
                return Status.CHOKING;
            } else if (!assignments.hasAssignments(peer)) {
                return Status.READY;
            }

            if (connectionState.getCurrentAssignment().isPresent()) {
                if (connectionState.getLastReceivedBlock().isPresent()) {
                    long timeSinceLastReceivedBlock = System.currentTimeMillis() - connectionState.getLastReceivedBlock().get();
                    if (timeSinceLastReceivedBlock > MAX_PIECE_RECEIVING_TIME.toMillis()) {
                        return Status.TIMEOUT;
                    } else if (timeSinceLastReceivedBlock > MAX_EXPECTED_BLOCK_RECEIVING_INTERVAL.toMillis()) {
                        return Status.WAITING;
                    }
                }
            }
            return Status.ACTIVE;
        }
    }

    private TorrentId torrentId;
    private Bitfield bitfield;
    private Assignments assignments;
    private PieceSelector pieceSelector;
    private BitfieldBasedStatistics pieceStatistics;
    private IMessageDispatcher dispatcher;

    private IPeerWorkerFactory peerWorkerFactory;
    private ConcurrentMap<Peer, PieceAnnouncingPeerWorker> peerMap;

    private long lastUpdated;

    private Map<Peer, AssignmentStatus> interestingPeers;
    private Set<Peer> timeoutedPeers;
    private Map<Peer, Message> interestUpdates;

    public TorrentWorker(TorrentId torrentId,
                         Bitfield bitfield,
                         Assignments assignments,
                         PieceSelector pieceSelector,
                         BitfieldBasedStatistics pieceStatistics,
                         IMessageDispatcher dispatcher,
                         IPeerWorkerFactory peerWorkerFactory) {
        this.torrentId = torrentId;
        this.bitfield = bitfield;
        this.assignments = assignments;
        this.pieceSelector = pieceSelector;
        this.pieceStatistics = pieceStatistics;
        this.dispatcher = dispatcher;
        this.peerWorkerFactory = peerWorkerFactory;

        this.peerMap = new ConcurrentHashMap<>();
        this.interestingPeers = new ConcurrentHashMap<>();
        this.timeoutedPeers = ConcurrentHashMap.newKeySet();
        this.interestUpdates = new ConcurrentHashMap<>();
    }

    /**
     * Called when a peer joins the torrent processing session.
     *
     * @since 1.0
     */
    public void addPeer(Peer peer) {
        PieceAnnouncingPeerWorker worker = createPeerWorker(peer);
        PieceAnnouncingPeerWorker existing = peerMap.putIfAbsent(peer, worker);
        if (existing == null) {
            dispatcher.addMessageConsumer(peer, message -> consume(peer, message));
            dispatcher.addMessageSupplier(peer, () -> produce(peer));
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Added connection for peer: " + peer);
            }
        }
    }

    private void consume(Peer peer, Message message) {
        PieceAnnouncingPeerWorker worker = getWorker(peer);
        worker.accept(message);
    }

    public Message produce(Peer peer) {
        PieceAnnouncingPeerWorker worker = getWorker(peer);
        if (mightUpdateAssignments()) {
            updateAssignments();
            lastUpdated = System.currentTimeMillis();
        }
        Message interestUpdate = interestUpdates.remove(peer);
        return (interestUpdate == null) ? worker.get() : interestUpdate;
    }

    private PieceAnnouncingPeerWorker getWorker(Peer peer) {
        return Objects.requireNonNull(peerMap.get(peer), "Unknown peer: " + peer);
    }

    private boolean mightUpdateAssignments() {
        return ((bitfield.getPiecesRemaining() > 0)
                    && (timeSinceLastUpdated() > UPDATE_ASSIGNMENTS_OPTIONAL_INTERVAL.toMillis())
                    && (assignments.getAssigneesCount() < MAX_CONCURRENT_ACTIVE_CONNECTIONS))
            || timeSinceLastUpdated() > UPDATE_ASSIGNMENTS_MANDATORY_INTERVAL.toMillis();
    }

    private long timeSinceLastUpdated() {
        return System.currentTimeMillis() - lastUpdated;
    }

    private void updateAssignments() {
        interestUpdates.clear();

        Set<Peer> readyPeers = new HashSet<>();
        Set<Peer> chokingPeers = new HashSet<>();

        Iterator<Map.Entry<Peer, AssignmentStatus>> iter = interestingPeers.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Peer, AssignmentStatus> e = iter.next();
            Peer peer = e.getKey();
            AssignmentStatus status = e.getValue();

            switch (status.getStatus()) {
                case TIMEOUT: {
                    iter.remove();
                    if (assignments.hasAssignments(peer)) {
                        timeoutedPeers.add(peer);
                        assignments.removeAssignments(peer);
                        getWorker(peer).getConnectionState().onUnassign();
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("Peer assignment removed due to TIMEOUT: peer {" + peer + "}");
                        }
                    }
                    continue;
                }
                case READY: {
                    readyPeers.add(peer);
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Peer is READY for next assignment: peer {" + peer + "}");
                    }
                    continue;
                }
                case CHOKING: {
                    chokingPeers.add(peer);
                    if (assignments.hasAssignments(peer)) {
                        assignments.removeAssignments(peer);
                        getWorker(peer).getConnectionState().onUnassign();
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace("Peer assignment removed due to CHOKING: peer {" + peer + "}");
                        }
                    }
                    continue;
                }
                case WAITING: {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Peer assignment is WAITING: peer {" + peer + "}");
                    }
                    continue;
                }
                case ACTIVE: {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Peer assignment is ACTIVE: peer {" + peer + "}");
                    }
                    continue;
                }
                default: {
                    throw new IllegalStateException("Unexpected status: " + status.getStatus().name());
                }
            }
        }

        Set<Peer> inactivePeers = new HashSet<>(peerMap.keySet());
        inactivePeers.removeAll(interestingPeers.keySet());
        inactivePeers.removeAll(timeoutedPeers);

        inactivePeers.forEach(p -> {
            ConnectionState connectionState = getWorker(p).getConnectionState();
            if (connectionState.isPeerChoking()) {
                chokingPeers.add(p);
            } else {
                readyPeers.add(p);
            }
        });

        Iterator<Integer> suggestedPieces = pieceSelector.getNextPieces(pieceStatistics).iterator();
        LOGGER.info("Assignees count: " + assignments.getAssigneesCount());
        LOGGER.info("Suggested pieces has next: " + suggestedPieces.hasNext());
        LOGGER.info("Ready peers count: " + readyPeers.size());
        LOGGER.info("Choking peers count: " + chokingPeers.size());
        while (suggestedPieces.hasNext() && readyPeers.size() > 0
                && assignments.getAssigneesCount() < MAX_CONCURRENT_ACTIVE_CONNECTIONS) {

            Integer suggestedPiece = suggestedPieces.next();

            Iterator<Peer> readyPeersIter = readyPeers.iterator();
            while (readyPeersIter.hasNext()) {
                Peer readyPeer = readyPeersIter.next();
                if (hasPiece(readyPeer, suggestedPiece)) {
                    if (!interestingPeers.containsKey(readyPeer)) {
                        interestingPeers.put(readyPeer,
                                new AssignmentStatus(readyPeer, getWorker(readyPeer).getConnectionState(), assignments));
                    }
                    assignments.assignPiece(readyPeer, suggestedPiece);
                    if (assignments.getAssignmentCount(readyPeer) >= MAX_ASSIGNED_PIECES_PER_PEER) {
                        readyPeersIter.remove();
                    }
                }
            }
        }

        // determine peers that do not have required pieces and are not interesting anymore
        readyPeers.forEach(p -> {
            if (!assignments.hasAssignments(p) && !hasInterestingPieces(p)) {
                ConnectionState connectionState = getWorker(p).getConnectionState();
                if (connectionState.isInterested()) {
                    interestUpdates.put(p, NotInterested.instance());
                    connectionState.setInterested(false);
                }
                interestingPeers.remove(p);
            }
        });
        chokingPeers.forEach(p -> {
            ConnectionState connectionState = getWorker(p).getConnectionState();
            if (hasInterestingPieces(p)) {
                if (!interestingPeers.containsKey(p)) {
                    interestingPeers.put(p,
                            new AssignmentStatus(p, getWorker(p).getConnectionState(), assignments));
                }
            } else {
                if (connectionState.isInterested()) {
                    interestUpdates.put(p, NotInterested.instance());
                    connectionState.setInterested(false);
                }
                interestingPeers.remove(p);
            }
        });
        interestingPeers.forEach((p, status) -> {
            ConnectionState connectionState = getWorker(p).getConnectionState();
            if (!connectionState.isInterested()) {
                interestUpdates.put(p, Interested.instance());
                connectionState.setInterested(true);
            }
        });
    }

    private boolean hasInterestingPieces(Peer peer) {
        Optional<Bitfield> peerBitfieldOptional = pieceStatistics.getPeerBitfield(peer);
        if (!peerBitfieldOptional.isPresent()) {
            return false;
        }
        BitSet peerBitfield = BitSet.valueOf(peerBitfieldOptional.get().getBitmask());
        BitSet localBitfield = BitSet.valueOf(bitfield.getBitmask());
        peerBitfield.andNot(localBitfield);
        return peerBitfield.cardinality() > 0;
    }

    private boolean hasPiece(Peer peer, Integer pieceIndex) {
        Optional<Bitfield> bitfield = pieceStatistics.getPeerBitfield(peer);
        return bitfield.isPresent() && bitfield.get().isVerified(pieceIndex);
    }

    private PieceAnnouncingPeerWorker createPeerWorker(Peer peer) {
        return new PieceAnnouncingPeerWorker(peerWorkerFactory.createPeerWorker(torrentId, peer));
    }

    /**
     * Called when a peer leaves the torrent processing session.
     *
     * @since 1.0
     */
    public void removePeer(Peer peer) {
        PeerWorker removed = peerMap.remove(peer);
        if (removed != null) {
            pieceStatistics.removeBitfield(peer);
            assignments.removeAssignments(peer);
            interestingPeers.remove(peer);
            interestUpdates.remove(peer);
            timeoutedPeers.remove(peer);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Removed connection for peer: " + peer);
            }
        }
    }

    /**
     * Get all peers, that this torrent worker is currently working with.
     *
     * @since 1.0
     */
    public Set<Peer> getPeers() {
        return peerMap.keySet();
    }

    /**
     * Get the current state of a connection with a particular peer.
     *
     * @return Connection state or null, if the peer is not connected to this torrent worker
     * @since 1.0
     */
    public ConnectionState getConnectionState(Peer peer) {
        PeerWorker worker = peerMap.get(peer);
        return (worker == null) ? null : worker.getConnectionState();
    }

    private class PieceAnnouncingPeerWorker implements PeerWorker {

        private final PeerWorker delegate;
        private final Queue<Have> pieceAnnouncements;

        PieceAnnouncingPeerWorker(PeerWorker delegate) {
            this.delegate = delegate;
            this.pieceAnnouncements = new ConcurrentLinkedQueue<>();
        }

        @Override
        public ConnectionState getConnectionState() {
            return delegate.getConnectionState();
        }

        @Override
        public void accept(Message message) {
            delegate.accept(message);
        }

        @Override
        public Message get() {
            Message message = pieceAnnouncements.poll();;
            if (message != null) {
                return message;
            }

            message = delegate.get();
            if (message != null && Have.class.equals(message.getClass())) {
                Have have = (Have) message;
                peerMap.values().forEach(worker -> {
                    if (this != worker) {
                        worker.getPieceAnnouncements().add(have);
                    }
                });
            }
            return message;
        }

        Queue<Have> getPieceAnnouncements() {
            return pieceAnnouncements;
        }
    }
}
