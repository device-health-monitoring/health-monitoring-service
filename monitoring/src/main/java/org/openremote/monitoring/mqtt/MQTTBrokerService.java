/*
 * Copyright 2022, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.monitoring.mqtt;

import io.netty.channel.ChannelId;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.core.client.impl.ClientSessionInternal;
import org.apache.activemq.artemis.core.config.WildcardConfiguration;
import org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil;
import org.apache.activemq.artemis.core.security.impl.SecurityStoreImpl;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerConnectionPlugin;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerSessionPlugin;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.camel.builder.RouteBuilder;
import org.apache.commons.lang3.tuple.Triple;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.timer.TimerService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.security.User;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Debouncer;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_QOS_LEVEL_KEY;
import static org.openremote.container.persistence.PersistenceService.PERSISTENCE_TOPIC;
import static org.openremote.container.util.MapAccess.getInteger;
import static org.openremote.container.util.MapAccess.getString;
import static org.openremote.model.syslog.SyslogCategory.API;

public class MQTTBrokerService extends RouteBuilder implements ContainerService, ActiveMQServerConnectionPlugin, ActiveMQServerSessionPlugin {

    public static final int PRIORITY = MED_PRIORITY;
    public static final String MQTT_CLIENT_QUEUE = "seda://MqttClientQueue?waitForTaskToComplete=IfReplyExpected&timeout=10000&purgeWhenStopping=true&discardIfNoConsumers=false&size=25000";
    public static final String MQTT_SERVER_LISTEN_HOST = "MQTT_SERVER_LISTEN_HOST";
    public static final String MQTT_SERVER_LISTEN_PORT = "MQTT_SERVER_LISTEN_PORT";
    protected final WildcardConfiguration wildcardConfiguration = new WildcardConfiguration();
    protected static final Logger LOG = SyslogCategory.getLogger(API, MQTTBrokerService.class);


    protected MessageBrokerService messageBrokerService;
    protected ScheduledExecutorService executorService;
    protected TimerService timerService;
    protected ConcurrentMap<String, RemotingConnection> clientIDConnectionMap = new ConcurrentHashMap<>();
    // TODO: Make auto provisioning clients disconnect and reconnect with credentials
    // Temp to prevent breaking existing auto provisioning API
    protected ConcurrentMap<Object, Triple<String, String, String>> transientCredentials = new ConcurrentHashMap<>();
    protected Debouncer<String> userAssetDisconnectDebouncer;

    protected boolean active;
    protected String host;
    protected int port;
    protected EmbeddedActiveMQ server;
    protected ClientSessionInternal internalSession;

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void init(Container container) throws Exception {
        host = getString(container.getConfig(), MQTT_SERVER_LISTEN_HOST, "0.0.0.0");
        port = getInteger(container.getConfig(), MQTT_SERVER_LISTEN_PORT, 1883);


        messageBrokerService = container.getService(MessageBrokerService.class);
        executorService = container.getExecutorService();
        timerService = container.getService(TimerService.class);
        userAssetDisconnectDebouncer = new Debouncer<>(executorService, this::forceDisconnectUser, 10000);


    }

    @Override
    public void start(Container container) throws Exception {
        LOG.info("Session created");


    }

    @SuppressWarnings("unchecked")
    @Override
    public void configure() throws Exception {
        from(PERSISTENCE_TOPIC)
            .routeId("UserPersistenceChanges")
            .filter(body().isInstanceOf(PersistenceEvent.class))
            .process(exchange -> {
                PersistenceEvent<?> persistenceEvent = (PersistenceEvent<?>)exchange.getIn().getBody(PersistenceEvent.class);

                if (persistenceEvent.getEntity() instanceof User user) {

                    if (!user.isServiceAccount()) {
                        return;
                    }

                    boolean forceDisconnect = persistenceEvent.getCause() == PersistenceEvent.Cause.DELETE;

                    if (persistenceEvent.getCause() == PersistenceEvent.Cause.UPDATE) {
                        // Force disconnect if certain properties have changed
                        forceDisconnect = Arrays.stream(persistenceEvent.getPropertyNames()).anyMatch((propertyName) ->
                            (propertyName.equals("enabled") && !user.getEnabled())
                                || propertyName.equals("username")
                                || propertyName.equals("secret"));
                    }

                    if (forceDisconnect) {
                        LOG.info("User modified or deleted so force closing any sessions for this user: " + user);
                        // Find existing connection for this user
                        forceDisconnectUser(user.getId());
                    }

                } else if (persistenceEvent.getEntity() instanceof UserAssetLink userAssetLink) {
                    String userID = userAssetLink.getId().getUserId();

                    // Debounce force disconnect of this user's sessions
                    userAssetDisconnectDebouncer.call(userID);
                }
            });
    }

    @Override
    public void stop(Container container) throws Exception {
        server.stop();
        LOG.fine("Stopped MQTT broker");


    }

    @Override
    public void afterCreateConnection(RemotingConnection connection) throws ActiveMQException {

    }

    @Override
    public void afterCreateSession(ServerSession session) throws ActiveMQException {

    }

    @Override
    public void afterDestroyConnection(RemotingConnection connection) throws ActiveMQException {

    }

    public void onSubscribe(RemotingConnection connection, String topicStr) {

    }

    public void onUnsubscribe(RemotingConnection connection, String topicStr) {

    }


    public Runnable getForceDisconnectRunnable(RemotingConnection connection) {
        return () -> doForceDisconnect(connection);
    }

    public void forceDisconnectUser(String userID) {
        if (TextUtil.isNullOrEmpty(userID)) {
            return;
        }
        getUserConnections(userID).forEach(this::doForceDisconnect);
    }

    public Set<RemotingConnection> getUserConnections(String userID) {
        return null;
    }

    protected void doForceDisconnect(RemotingConnection connection) {
        LOG.fine("Force disconnecting session: " + connection);
        connection.disconnect(false);
    }

    public void publishMessage(String topic, Object data, MqttQoS qoS) {
        try {
            if (internalSession != null) {
                ClientProducer producer = internalSession.createProducer(MQTTUtil.convertMqttTopicFilterToCoreAddress(topic, server.getConfiguration().getWildcardConfiguration()));
                ClientMessage message = internalSession.createMessage(false);
                message.putIntProperty(MQTT_QOS_LEVEL_KEY, qoS.value());
                message.writeBodyBufferBytes(ValueUtil.asJSON(data).map(String::getBytes).orElseThrow(() -> new IllegalStateException("Failed to convert payload to JSON string: " + data)));
                producer.send(message);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Couldn't send AttributeEvent to MQTT client", e);
        }
    }

    public WildcardConfiguration getWildcardConfiguration() {
        return wildcardConfiguration;
    }

    public static String getConnectionIDString(RemotingConnection connection) {
        if (connection == null) {
            return null;
        }

        Object ID = connection.getID();
        return ID instanceof ChannelId ? ((ChannelId)ID).asLongText() : ID.toString();
    }

    public RemotingConnection getConnectionFromClientID(String clientID) {
        if (TextUtil.isNullOrEmpty(clientID)) {
            return null;
        }

        // This logic is needed because the connection clientID isn't populated when afterCreateConnection is called
        RemotingConnection connection = clientIDConnectionMap.get(clientID);

        if (connection == null) {
            // Try and find the client ID from the sessions
            for (ServerSession serverSession : server.getActiveMQServer().getSessions()) {
                if (serverSession.getRemotingConnection() != null && Objects.equals(clientID, serverSession.getRemotingConnection().getClientID())) {
                    connection = serverSession.getRemotingConnection();
                    clientIDConnectionMap.put(clientID, connection);
                    break;
                }
            }
        }

        return connection;
    }

    // TODO: Remove this
    public void addTransientCredentials(RemotingConnection connection, Triple<String, String, String> userIDNameAndPassword) {
        LOG.fine("Adding transient user credentials: connection ID=" + connection.getID() + " ,username=" + userIDNameAndPassword.getMiddle());
        transientCredentials.put(connection.getID(), userIDNameAndPassword);
        ((SecurityStoreImpl)server.getActiveMQServer().getSecurityStore()).invalidateAuthenticationCache();
    }

    // TODO: Remove this
    public void removeTransientCredentials(RemotingConnection connection) {
        LOG.fine("Removing transient user credentials: connection ID=" + connection.getID());
        transientCredentials.remove(connection.getID());
        ((SecurityStoreImpl)server.getActiveMQServer().getSecurityStore()).invalidateAuthenticationCache();
        ((SecurityStoreImpl)server.getActiveMQServer().getSecurityStore()).invalidateAuthorizationCache();
    }
}
