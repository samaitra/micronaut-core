/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.configuration.lettuce.session;

import io.micronaut.configuration.lettuce.RedisSetting;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.serialize.ObjectSerializer;
import io.micronaut.core.util.Toggleable;
import io.micronaut.session.http.HttpSessionConfiguration;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuration properties for Redis session.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@ConfigurationProperties(RedisSetting.PREFIX)
public class RedisHttpSessionConfiguration extends HttpSessionConfiguration implements Toggleable {

    private WriteMode writeMode = WriteMode.BATCH;
    private boolean enableKeyspaceEvents = true;
    private String namespace = "micronaut:session:";
    private String activeSessionsKey = namespace + "active-sessions";
    private String sessionCreatedTopic = namespace + "event:session-created";
    private Class<ObjectSerializer> valueSerializer;
    private Charset charset = StandardCharsets.UTF_8;
    private Duration expiredSessionCheck = Duration.ofMinutes(1);
    private String serverName;

    /**
     * @return The name of the a configured Redis server to use.
     */
    public Optional<String> getServerName() {
        return Optional.ofNullable(serverName);
    }

    /**
     * @param serverName The server name.
     */
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    /**
     * @return The topic to use to publish the creation of new sessions.
     */
    public String getSessionCreatedTopic() {
        return sessionCreatedTopic;
    }

    /**
     * @param sessionCreatedTopic The topic to publish the creation of new sessions.
     */
    public void setSessionCreatedTopic(String sessionCreatedTopic) {
        this.sessionCreatedTopic = sessionCreatedTopic;
    }

    /**
     * @return The key of the sorted set used to maintain a set of active sessions.
     */
    public String getActiveSessionsKey() {
        return activeSessionsKey;
    }

    /**
     * @param activeSessionsKey The key used to maintain a set of active sessions
     */
    public void setActiveSessionsKey(String activeSessionsKey) {
        this.activeSessionsKey = activeSessionsKey;
    }

    /**
     * @return The key prefix to use for reading and writing sessions
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * @param namespace The key prefix to use for reading and writing sessions
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    /**
     * @return The {@link ObjectSerializer} type to use for serializing values. Defaults to {@link io.micronaut.core.serialize.JdkSerializer}
     */
    public Optional<Class<ObjectSerializer>> getValueSerializer() {
        return Optional.ofNullable(valueSerializer);
    }

    /**
     * @param valueSerializer The {@link ObjectSerializer} type to use for serializing values
     */
    public void setValueSerializer(Class<ObjectSerializer> valueSerializer) {
        this.valueSerializer = valueSerializer;
    }

    /**
     * @return The charset to use when encoding sessions
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * @return Whether keyspace events should be enabled programmatically
     */
    public boolean isEnableKeyspaceEvents() {
        return enableKeyspaceEvents;
    }

    /**
     * @param enableKeyspaceEvents Whether keyspace events should be enabled programmatically
     */
    public void setEnableKeyspaceEvents(boolean enableKeyspaceEvents) {
        this.enableKeyspaceEvents = enableKeyspaceEvents;
    }

    /**
     * @return The {@link RedisHttpSessionConfiguration.WriteMode} to use. Defaults to {@link RedisHttpSessionConfiguration.WriteMode#BATCH}
     */
    public WriteMode getWriteMode() {
        return writeMode;
    }

    /**
     * @param writeMode The {@link RedisHttpSessionConfiguration.WriteMode}
     */
    public void setWriteMode(WriteMode writeMode) {
        this.writeMode = writeMode;
    }

    /**
     * @return The duration with which to check for expired sessions
     */
    public Duration getExpiredSessionCheck() {
        return expiredSessionCheck;
    }

    /**
     * @param expiredSessionCheck The duration with which to check for expired sessions
     */
    public void setExpiredSessionCheck(Duration expiredSessionCheck) {
        this.expiredSessionCheck = expiredSessionCheck;
    }

    /**
     * The write mode for saving the session data.
     */
    enum WriteMode {
        /**
         * Batch up changes an synchronize once only when {@link io.micronaut.session.SessionStore#save(io.micronaut.session.Session)} is called.
         */
        BATCH,
        /**
         * <p>Perform asynchronous write-behind when session attributes are changed in addition to batching up changes when  {@link io.micronaut.session.SessionStore#save(io.micronaut.session.Session)} is called</p>.
         *
         * <p>Errors that occur during these asynchronous operations are silently ignored</p>
         *
         * <p>This strategy has the advantage of providing greater consistency at the expense of more network traffic</p>
         */
        BACKGROUND;
    }
}
