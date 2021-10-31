/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package org.geysermc.floodgate.database;

import com.mysql.cj.jdbc.MysqlConnectionPoolDataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.sql.PooledConnection;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.floodgate.api.link.LinkRequest;
import org.geysermc.floodgate.api.link.LinkRequestResult;
import org.geysermc.floodgate.database.config.MysqlConfig;
import org.geysermc.floodgate.link.CommonPlayerLink;
import org.geysermc.floodgate.link.LinkRequestImpl;
import org.geysermc.floodgate.util.LinkedPlayer;

public class MysqlDatabase extends CommonPlayerLink {
    private MysqlConnectionPoolDataSource dataSource;

    @Override
    public void load() {
        getLogger().info("Connecting to a MySQL-like database...");
        try {
            Class.forName("com.mysql.jdbc.Driver");
            MysqlConfig databaseconfig = getConfig(MysqlConfig.class);

            dataSource = new MysqlConnectionPoolDataSource();
            String host;
            String port;

            String hostname = databaseconfig.getHostname();
            if (hostname.contains(":")) {
                String[] split = hostname.split(":");
                host = split[0];
                try {
                    port = split[1];
                } catch (NumberFormatException exception) {
                    getLogger().info("{} is not a valid port! Will use the default port", split[1]);
                    port = "3306";
                }
            } else {
                host = hostname;
                port = "3306";
            }

            dataSource.setUrl(String.format(
                    "jdbc:mysql://%s:%s/%s",
                    host, port, databaseconfig.getDatabase()
            ));
            dataSource.setUser(databaseconfig.getUsername());
            dataSource.setPassword(databaseconfig.getPassword());

            try (Connection connection = dataSource.getConnection()) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "CREATE TABLE IF NOT EXISTS `LinkedPlayers` ( " +
                                    "`bedrockId` BINARY(16) NOT NULL , " +
                                    "`javaUniqueId` BINARY(16) NOT NULL , " +
                                    "`javaUsername` VARCHAR(16) NOT NULL , " +
                                    " PRIMARY KEY (`bedrockId`) , " +
                                    " INDEX (`bedrockId`, `javaUniqueId`)" +
                                    ") ENGINE = InnoDB;"
                    );
                    statement.executeUpdate(
                            "CREATE TABLE IF NOT EXISTS `LinkedPlayersRequest` ( " +
                                    "`javaUsername` VARCHAR(16) NOT NULL , `javaUniqueId` BINARY(16) NOT NULL , " +
                                    "`linkCode` VARCHAR(16) NOT NULL , " +
                                    "`bedrockUsername` VARCHAR(16) NOT NULL ," +
                                    "`requestTime` BIGINT NOT NULL , " +
                                    " PRIMARY KEY (`javaUsername`), INDEX(`requestTime`)" +
                                    " ) ENGINE = InnoDB;"
                    );
                }
            }
            getLogger().info("Connected to MySQL-like database.");
        } catch (ClassNotFoundException exception) {
            getLogger().error("The required class to load the MySQL database wasn't found");
        } catch (SQLException exception) {
            getLogger().error("Error while loading database", exception);
        }
    }

    @Override
    public void stop() {
        super.stop();
        try {
            PooledConnection pooledConnection = dataSource.getPooledConnection();
            if (pooledConnection != null) {
                pooledConnection.close();
            }
        } catch (SQLException e) {
            getLogger().error("Failed to close pooled connection.");
        }
    }

    @Override
    @NonNull
    public CompletableFuture<LinkedPlayer> getLinkedPlayer(@NonNull UUID bedrockId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT * FROM `LinkedPlayers` WHERE `bedrockId` = ?"
                )) {
                    query.setBytes(1, uuidToBytes(bedrockId));
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) {
                            return null;
                        }
                        String javaUsername = result.getString("javaUsername");
                        UUID javaUniqueId = bytesToUUID(result.getBytes("javaUniqueId"));
                        return LinkedPlayer.of(javaUsername, javaUniqueId, bedrockId);
                    }
                }
            } catch (SQLException exception) {
                getLogger().error("Error while getting LinkedPlayer", exception);
                throw new CompletionException("Error while getting LinkedPlayer", exception);
            }
        }, getExecutorService());
    }

    @Override
    @NonNull
    public CompletableFuture<Boolean> isLinkedPlayer(@NonNull UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT * FROM `LinkedPlayers` WHERE `bedrockId` = ? OR `javaUniqueId` = ?"
                )) {
                    byte[] uuidBytes = uuidToBytes(playerId);
                    query.setBytes(1, uuidBytes);
                    query.setBytes(2, uuidBytes);
                    try (ResultSet result = query.executeQuery()) {
                        return result.next();
                    }
                }
            } catch (SQLException exception) {
                getLogger().error("Error while checking if player is a LinkedPlayer", exception);
                throw new CompletionException(
                        "Error while checking if player is a LinkedPlayer", exception
                );
            }
        }, getExecutorService());
    }

    @Override
    @NonNull
    public CompletableFuture<Void> linkPlayer(
            @NonNull UUID bedrockId,
            @NonNull UUID javaId,
            @NonNull String javaUsername) {
        return CompletableFuture.runAsync(
                () -> linkPlayer0(bedrockId, javaId, javaUsername),
                getExecutorService());
    }

    private void linkPlayer0(UUID bedrockId, UUID javaId, String javaUsername) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "INSERT INTO `LinkedPlayers` VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE " +
                            "`javaUniqueId`=VALUES(`javaUniqueId`), " +
                            "`javaUsername`=VALUES(`javaUsername`);"
            )) {
                query.setBytes(1, uuidToBytes(bedrockId));
                query.setBytes(2, uuidToBytes(javaId));
                query.setString(3, javaUsername);
                query.executeUpdate();
            }
        } catch (SQLException exception) {
            getLogger().error("Error while linking player", exception);
            throw new CompletionException("Error while linking player", exception);
        }
    }

    @Override
    @NonNull
    public CompletableFuture<Void> unlinkPlayer(@NonNull UUID javaId) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement query = connection.prepareStatement(
                        "DELETE FROM `LinkedPlayers` WHERE `javaUniqueId` = ? OR `bedrockId` = ?"
                )) {
                    byte[] uuidBytes = uuidToBytes(javaId);
                    query.setBytes(1, uuidBytes);
                    query.setBytes(2, uuidBytes);
                    query.executeUpdate();
                }
            } catch (SQLException exception) {
                getLogger().error("Error while unlinking player", exception);
                throw new CompletionException("Error while unlinking player", exception);
            }
        }, getExecutorService());
    }

    @Override
    @NonNull
    public CompletableFuture<String> createLinkRequest(
            @NonNull UUID javaId,
            @NonNull String javaUsername,
            @NonNull String bedrockUsername) {
        return CompletableFuture.supplyAsync(() -> {
            String linkCode = createCode();

            createLinkRequest0(javaUsername, javaId, linkCode, bedrockUsername);

            return linkCode;
        }, getExecutorService());
    }

    private void createLinkRequest0(
            String javaUsername,
            UUID javaId,
            String linkCode,
            String bedrockUsername) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "INSERT INTO `LinkedPlayersRequest` VALUES (?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "`javaUniqueId`=VALUES(`javaUniqueId`), " +
                            "`linkCode`=VALUES(`linkCode`), " +
                            "`bedrockUsername`=VALUES(`bedrockUsername`), " +
                            "`requestTime`=VALUES(`requestTime`);"
            )) {
                query.setString(1, javaUsername);
                query.setBytes(2, uuidToBytes(javaId));
                query.setString(3, linkCode);
                query.setString(4, bedrockUsername);
                query.setLong(5, Instant.now().getEpochSecond());
                query.executeUpdate();
            }
        } catch (SQLException exception) {
            getLogger().error("Error while linking player", exception);
            throw new CompletionException("Error while linking player", exception);
        }
    }

    private void removeLinkRequest(String javaUsername) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "DELETE FROM `LinkedPlayersRequest` WHERE `javaUsername` = ?"
            )) {
                query.setString(1, javaUsername);
                query.executeUpdate();
            }
        } catch (SQLException exception) {
            getLogger().error("Error while cleaning up LinkRequest", exception);
        }
    }

    @Override
    @NonNull
    public CompletableFuture<LinkRequestResult> verifyLinkRequest(
            @NonNull UUID bedrockId,
            @NonNull String javaUsername,
            @NonNull String bedrockUsername,
            @NonNull String code) {
        return CompletableFuture.supplyAsync(() -> {
            LinkRequest request = getLinkRequest0(javaUsername);

            if (request == null || !isRequestedPlayer(request, bedrockId)) {
                return LinkRequestResult.NO_LINK_REQUESTED;
            }

            if (!request.getLinkCode().equals(code)) {
                return LinkRequestResult.INVALID_CODE;
            }

            // link request can be removed. Doesn't matter if the request is expired or not
            removeLinkRequest(javaUsername);

            if (request.isExpired(getVerifyLinkTimeout())) {
                return LinkRequestResult.REQUEST_EXPIRED;
            }

            linkPlayer0(bedrockId, request.getJavaUniqueId(), javaUsername);
            return LinkRequestResult.LINK_COMPLETED;
        }, getExecutorService());
    }

    private LinkRequest getLinkRequest0(String javaUsername) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT * FROM `LinkedPlayersRequest` WHERE `javaUsername` = ?"
            )) {
                query.setString(1, javaUsername);

                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        UUID javaId = bytesToUUID(result.getBytes(2));
                        String linkCode = result.getString(3);
                        String bedrockUsername = result.getString(4);
                        long requestTime = result.getLong(5);
                        return new LinkRequestImpl(javaUsername, javaId, linkCode, bedrockUsername,
                                requestTime);
                    }
                }
            }
        } catch (SQLException exception) {
            getLogger().error("Error while getLinkRequest", exception);
            throw new CompletionException("Error while getLinkRequest", exception);
        }
        return null;
    }

    public void cleanLinkRequests() {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "DELETE FROM `LinkedPlayersRequest` WHERE `requestTime` < ?"
            )) {
                query.setLong(1, Instant.now().getEpochSecond() - getVerifyLinkTimeout());
                query.executeUpdate();
            }
        } catch (SQLException exception) {
            getLogger().error("Error while cleaning up link requests", exception);
        }
    }

    private byte[] uuidToBytes(UUID uuid) {
        byte[] uuidBytes = new byte[16];
        ByteBuffer.wrap(uuidBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        return uuidBytes;
    }

    private UUID bytesToUUID(byte[] uuidBytes) {
        ByteBuffer buf = ByteBuffer.wrap(uuidBytes);
        return new UUID(buf.getLong(), buf.getLong());
    }

}
