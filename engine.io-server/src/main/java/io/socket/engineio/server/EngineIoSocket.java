package io.socket.engineio.server;

import io.socket.emitter.Emitter;
import io.socket.engineio.parser.Packet;
import io.socket.engineio.server.transport.Polling;
import io.socket.engineio.server.transport.WebSocket;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An engine.io socket.
 *
 * Objects of this class represents connections to remote clients
 * one per object.
 */
public final class EngineIoSocket extends Emitter {

    private static final List<Packet<?>> PAYLOAD_NOOP = new ArrayList<Packet<?>>() {{
        add(new Packet<>(Packet.NOOP));
    }};

    private final String mSid;
    private final EngineIoServer mServer;
    private final LinkedList<Packet<?>> mWriteBuffer = new LinkedList<>();
    private final Runnable mPingTask = this::sendPing;
    private final Runnable mPingTimeoutTask = () -> onClose("ping timeout", null);

    private final Object mLockObject;
    private final ScheduledExecutorService mScheduledTaskHandler;
    private ScheduledFuture<?> mPingFuture = null;
    private ScheduledFuture<?> mPintTimeoutFuture = null;

    private final AtomicBoolean mUpgrading = new AtomicBoolean(false);
    private Runnable mCleanupFunction = null;
    private ReadyState mReadyState;
    private Transport mTransport;
    private Map<String, String> mInitialQuery;
    private Map<String, List<String>> mInitialHeaders;

    EngineIoSocket(Object lockObject, String sid, EngineIoServer server, ScheduledExecutorService scheduledTaskHandler) {
        mLockObject = lockObject;

        mSid = sid;
        mServer = server;
        mScheduledTaskHandler = scheduledTaskHandler;

        mReadyState = ReadyState.OPENING;
    }

    /**
     * Gets the sid of this socket.
     *
     * @return String sid value.
     */
    @SuppressWarnings("WeakerAccess")
    public String getId() {
        return mSid;
    }

    /**
     * Gets the ready state of this socket.
     *
     * @return Socket ready state.
     */
    @SuppressWarnings("WeakerAccess")
    public ReadyState getReadyState() {
        return mReadyState;
    }

    /**
     * Get the query parameters of the initial HTTP connection.
     *
     * @return Query parameters of the initial HTTP connection.
     */
    public Map<String, String> getInitialQuery() {
        return mInitialQuery;
    }

    /**
     * Get the headers of the initial HTTP connection.
     *
     * @return Headers of the initial HTTP connection.
     */
    public Map<String, List<String>> getInitialHeaders() {
        return mInitialHeaders;
    }

    /**
     * Send a packet to the remote client.
     * Queuing of packets in case of polling transport are handled internally.
     * This method is thread safe.
     *
     * @param packet The packet to send.
     */
    public void send(Packet<?> packet) {
        sendPacket(packet);
    }

    /**
     * Close this socket.
     */
    public void close() {
        if(mReadyState == ReadyState.OPEN) {
            mReadyState = ReadyState.CLOSING;

            if(mWriteBuffer.size() > 0) {
                mTransport.on("drain", args -> closeTransport());
            } else {
                closeTransport();
            }
        }
    }

    /**
     * Called after instance creation to initialize transport.
     *
     * @param transport The opened transport.
     * @param initialRequest The initial HTTP request the caused the connection.
     */
    void init(Transport transport, @SuppressWarnings("unused") HttpServletRequest initialRequest) {
        setTransport(transport);
        mInitialQuery = transport.getInitialQuery();
        mInitialHeaders = transport.getInitialHeaders();
        onOpen();
    }

    void updateInitialHeadersFromActiveTransport() {
        mInitialQuery = mTransport.getInitialQuery();
        mInitialHeaders = mTransport.getInitialHeaders();
    }

    /**
     * Handle an HTTP request.
     *
     * @param request The HTTP request object.
     * @param response The HTTP response object.
     * @throws IOException On IO error.
     */
    void onRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        mTransport.onRequest(request, response);

        if (mUpgrading.get() && mTransport.isWritable() && mWriteBuffer.isEmpty())
            mTransport.send(PAYLOAD_NOOP);
    }

    /**
     * Checks whether the socket can be upgraded to another transport.
     *
     * @param transport The transport to upgrade to.
     * @return Boolean value indicating if upgrade is possible.
     */
    @SuppressWarnings("SameParameterValue")
    boolean canUpgrade(String transport) {
        return (!mUpgrading.get() && mTransport.getName().equals(Polling.NAME) && transport.equals(WebSocket.NAME));
    }

    /**
     * Perform upgrade to the specified transport.
     *
     * @param transport The transport to upgrade to.
     */
    void upgrade(final Transport transport) {
        mUpgrading.set(true);

        final Runnable cleanup = () -> {
            mUpgrading.set(false);
            transport.off("packet");
            transport.off("close");
            transport.off("error");
        };

        final Listener onError = args -> {
            cleanup.run();
            transport.close();
        };

        transport.on("packet", args -> {
            final Packet<?> packet = (Packet<?>) args[0];
            if(packet.type.equals(Packet.PING) && (packet.data != null) && packet.data.equals("probe")) {
                final Packet<String> replyPacket = new Packet<>(Packet.PONG);
                replyPacket.data = "probe";

                transport.send(new ArrayList<Packet<?>>() {{
                    add(replyPacket);
                }});

                if (mTransport.isWritable()) {
                    mTransport.send(PAYLOAD_NOOP);
                }

                emit("upgrading", transport);
            } else if(packet.type.equals(Packet.UPGRADE) && (mReadyState != ReadyState.CLOSED) && (mReadyState != ReadyState.CLOSING)) {
                cleanup.run();
                clearTransport();
                setTransport(transport);
                emit("upgrade", transport);
                flush();

                schedulePing();
            } else {
                cleanup.run();
                transport.close();
            }
        });
        transport.once("close", args -> onError.call("transport closed"));
        transport.once("error", onError);

        once("close", args -> onError.call("socket closed"));
    }

    /**
     * Get the name of the current transport.
     *
     * @return Name of current transport.
     */
    String getCurrentTransportName() {
        return mTransport.getName();
    }

    private void setTransport(final Transport transport) {
        mTransport = transport;
        transport.once("error", args -> onError());
        transport.once("close", args -> {
            String description = (args.length > 0)? ((String) args[0]) : null;
            onClose("transport close", description);
        });
        transport.on("packet", args -> onPacket((Packet<?>) args[0]));
        transport.on("drain", args -> flush());

        mCleanupFunction = () -> {
            transport.off("error");
            transport.off("close");
            transport.off("packet");
            transport.off("drain");
        };
    }

    private void closeTransport() {
        mTransport.close();
    }

    private void clearTransport() {
        if(mCleanupFunction != null) {
            mCleanupFunction.run();
        }

        mTransport.close();
    }

    private void onOpen() {
        mReadyState = ReadyState.OPEN;

        JSONArray upgrades = new JSONArray();
        upgrades.put(WebSocket.NAME);

        JSONObject handshakePacket = new JSONObject();
        handshakePacket.put("sid", mSid);
        handshakePacket.put("upgrades", upgrades);
        handshakePacket.put("pingInterval", mServer.getOptions().getPingInterval());
        handshakePacket.put("pingTimeout", mServer.getOptions().getPingTimeout());

        Packet<String> openPacket = new Packet<>(Packet.OPEN);
        openPacket.data = handshakePacket.toString();

        sendPacket(openPacket);

        if (mServer.getOptions().getInitialPacket() != null) {
            sendPacket(mServer.getOptions().getInitialPacket());
        }

        emit("open");
        schedulePing();
    }

    private void onClose(String reason, String description) {
        if(mReadyState != ReadyState.CLOSED) {
            mReadyState = ReadyState.CLOSED;
            mPingFuture.cancel(false);

            clearTransport();
            emit("close", reason, description);
        }
    }

    private void onError() {
        onClose("transport error", null);
    }

    private void onPacket(Packet<?> packet) {
        if(mReadyState == ReadyState.OPEN) {
            emit("packet", packet);

            switch (packet.type) {
                case Packet.PONG:
                    schedulePing();
                    emit("heartbeat");
                    break;
                case Packet.ERROR:
                    onClose("parse error", null);
                    break;
                case Packet.MESSAGE:
                    emit("data", packet.data);
                    emit("message", packet.data);
                    break;
            }
        }
    }

    private void sendPacket(Packet<?> packet) {
        if((mReadyState != ReadyState.CLOSING) && (mReadyState != ReadyState.CLOSED)) {
            synchronized (mLockObject) {
                mWriteBuffer.add(packet);
            }

            flush();
        }
    }

    private void flush() {
        if((mReadyState != ReadyState.CLOSED) && (mTransport.isWritable()) && (mWriteBuffer.size() > 0)) {
            synchronized (mLockObject) {
                emit("flush", Collections.unmodifiableCollection(mWriteBuffer));

                mTransport.send(mWriteBuffer);
                mWriteBuffer.clear();
            }

            emit("drain");
        }
    }

    private void sendPing() {
        synchronized (mLockObject) {
            sendPacket(new Packet<>(Packet.PING));
            resetPingTimeout();
        }
    }

    private void schedulePing() {
        synchronized (mLockObject) {
            if(mPingFuture != null) {
                mPingFuture.cancel(false);
            }

            mPingFuture = mScheduledTaskHandler.schedule(
                    mPingTask,
                    mServer.getOptions().getPingInterval(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private void resetPingTimeout() {
        synchronized (mLockObject) {
            if(mPintTimeoutFuture != null) {
                mPintTimeoutFuture.cancel(false);
            }

            mPintTimeoutFuture = mScheduledTaskHandler.schedule(
                    mPingTimeoutTask,
                    mServer.getOptions().getPingTimeout(),
                    TimeUnit.MILLISECONDS);
        }
    }
}