/*
 * Microsoft JDBC Driver for SQL Server Copyright(c) Microsoft Corporation All rights reserved. This program is made
 * available under the terms of the MIT License. See the LICENSE file in the project root for more information.
 */

package com.microsoft.sqlserver.jdbc.resiliency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.PooledConnection;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.microsoft.sqlserver.jdbc.RandomUtil;
import com.microsoft.sqlserver.jdbc.SQLServerConnectionPoolDataSource;
import com.microsoft.sqlserver.jdbc.TestResource;
import com.microsoft.sqlserver.jdbc.TestUtils;
import com.microsoft.sqlserver.testframework.AbstractTest;
import com.microsoft.sqlserver.testframework.Constants;


@Tag(Constants.xSQLv11)
public class BasicConnectionTest extends AbstractTest {

    @Test
    public void testBasicReconnectDefault() throws SQLException {
        basicReconnect(connectionString);
    }

    @Test
    public void testBasicEncryptedConnection() throws SQLException {
        basicReconnect(connectionString + ";encrypt=true;trustServerCertificate=true;");
    }

    @Test
    public void testGracefulClose() throws SQLException {
        try (Connection c = ResiliencyUtils.getConnection(connectionString)) {
            try (Statement s = c.createStatement()) {
                ResiliencyUtils.killConnection(c, connectionString);
                c.close();
                s.executeQuery("SELECT 1");
                fail("Query execution did not throw an exception on a closed execution");
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("The connection is closed."));
            }
        }
    }

    @Test
    public void testSetAttributes() throws SQLException {
        try (Connection c = ResiliencyUtils.getConnection(connectionString)) {
            ResiliencyUtils.toggleRandomProperties(c);
            Map<String, String> expected = ResiliencyUtils.getUserOptions(c);
            ResiliencyUtils.killConnection(c, connectionString);
            Map<String, String> recieved = ResiliencyUtils.getUserOptions(c);
            assertTrue("User options do not match", expected.equals(recieved));
        }
    }

    @Test
    public void testCatalog() throws SQLException {
        String expectedDatabaseName = null;
        String actualDatabaseName = null;
        try (Connection c = ResiliencyUtils.getConnection(connectionString); Statement s = c.createStatement()) {
            expectedDatabaseName = RandomUtil.getIdentifier("resDB");
            TestUtils.dropDatabaseIfExists(expectedDatabaseName, connectionString);
            s.execute("CREATE DATABASE [" + expectedDatabaseName + "]");
            try {
                c.setCatalog(expectedDatabaseName);
            } catch (SQLException e) {
                // Switching databases is not supported against Azure, skip/
                return;
            }
            ResiliencyUtils.killConnection(c, connectionString);
            try (ResultSet rs = s.executeQuery("SELECT db_name();")) {
                while (rs.next()) {
                    actualDatabaseName = rs.getString(1);
                }
            }
            // Check if the driver reconnected to the expected database.
            assertEquals(expectedDatabaseName, actualDatabaseName);
        } finally {
            TestUtils.dropDatabaseIfExists(expectedDatabaseName, connectionString);
        }
    }

    @Test
    public void testUseDb() throws SQLException {
        String expectedDatabaseName = null;
        String actualDatabaseName = null;
        try (Connection c = ResiliencyUtils.getConnection(connectionString); Statement s = c.createStatement()) {
            expectedDatabaseName = RandomUtil.getIdentifier("resDB");
            TestUtils.dropDatabaseIfExists(expectedDatabaseName, connectionString);
            s.execute("CREATE DATABASE [" + expectedDatabaseName + "]");
            try {
                s.execute("USE [" + expectedDatabaseName + "]");
            } catch (SQLException e) {
                // Switching databases is not supported against Azure, skip/
                return;
            }
            ResiliencyUtils.killConnection(c, connectionString);
            try (ResultSet rs = s.executeQuery("SELECT db_name();")) {
                while (rs.next()) {
                    actualDatabaseName = rs.getString(1);
                }
            }
            // Check if the driver reconnected to the expected database.
            assertEquals(expectedDatabaseName, actualDatabaseName);
        } finally {
            TestUtils.dropDatabaseIfExists(expectedDatabaseName, connectionString);
        }
    }

    @Test
    public void testSetLanguage() throws SQLException {
        String expectedLanguage = "Italiano";
        String actualLanguage = "";
        try (Connection c = ResiliencyUtils.getConnection(connectionString); Statement s = c.createStatement()) {
            s.execute("SET LANGUAGE " + expectedLanguage);
            ResiliencyUtils.killConnection(c, connectionString);
            try (ResultSet rs = s.executeQuery("SELECT @@LANGUAGE")) {
                while (rs.next()) {
                    actualLanguage = rs.getString(1);
                }
            }
            // Check if the driver reconnected to the expected database.
            assertEquals(expectedLanguage, actualLanguage);
        }
    }

    @Test
    public void testOpenTransaction() throws SQLException {
        String tableName = RandomUtil.getIdentifier("resTable");
        try (Connection c = ResiliencyUtils.getConnection(connectionString); Statement s = c.createStatement()) {
            TestUtils.dropTableIfExists(tableName, s);
            s.execute("CREATE TABLE [" + tableName + "] (col1 varchar(1))");
            c.setAutoCommit(false);
            s.execute("INSERT INTO [" + tableName + "] values ('x')");
            ResiliencyUtils.killConnection(c, connectionString);
            try (ResultSet rs = s.executeQuery("SELECT db_name();")) {
                fail("Connection resiliency should not have reconnected with an open transaction!");
            } catch (SQLException ex) {
                String message = ex.getMessage();
                assertEquals(TestResource.getResource("R_crServerSessionStateNotRecoverable"), message);
            }
        }
        try (Connection c = DriverManager.getConnection(connectionString); Statement s = c.createStatement()) {
            TestUtils.dropTableIfExists(tableName, s);
        }
    }

    @Test
    public void testOpenResultSets() throws SQLException {
        try (Connection c = ResiliencyUtils.getConnection(connectionString); Statement s = c.createStatement()) {
            int sessionId = ResiliencyUtils.getSessionId(c);
            try (ResultSet rs = s.executeQuery("select top 100000 * from sys.columns cross join sys.columns as c2")) {
                rs.next();
                ResiliencyUtils.killConnection(sessionId, connectionString);
                s.execute("SELECT 1");
                fail("Connection resiliency should not have reconnected with open results!");
            } catch (SQLException ex) {
                String message = ex.getMessage();
                assertEquals(TestResource.getResource("R_connectionIsClosed"), message);
            }
        }
    }

    @Test
    public void testPooledConnection() throws SQLException {
        SQLServerConnectionPoolDataSource mds = new SQLServerConnectionPoolDataSource();
        mds.setURL(connectionString);
        PooledConnection pooledConnection = mds.getPooledConnection();
        try (Connection c = pooledConnection.getConnection(); Statement s = c.createStatement()) {
            ResiliencyUtils.minimizeIdleNetworkTrackerPooledConnection(c);
            c.close();
            Connection c1 = pooledConnection.getConnection();
            Statement s1 = c1.createStatement();
            ResiliencyUtils.killConnection(c1, connectionString);
            ResiliencyUtils.minimizeIdleNetworkTrackerPooledConnection(c1);
            s1.executeQuery("SELECT 1");
        } catch (SQLException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void testPooledConnectionDB() throws SQLException {
        SQLServerConnectionPoolDataSource mds = new SQLServerConnectionPoolDataSource();
        mds.setURL(connectionString);
        PooledConnection pooledConnection = mds.getPooledConnection();
        String newDBName = null;
        String resultDBName = null;
        String originalDBName = null;
        try (Connection c = pooledConnection.getConnection(); Statement s = c.createStatement()) {
            ResiliencyUtils.minimizeIdleNetworkTrackerPooledConnection(c);
            ResultSet rs = s.executeQuery("SELECT DB_NAME();");
            rs.next();
            originalDBName = rs.getString(1);
            newDBName = RandomUtil.getIdentifier("resDB");
            TestUtils.dropDatabaseIfExists(newDBName, connectionString);
            s.execute("CREATE DATABASE [" + newDBName + "]");
            try {
                s.execute("USE [" + newDBName + "]");
            } catch (SQLException e) {
                // Switching databases is not supported against Azure, skip/
                return;
            }
            c.close();
            Connection c1 = pooledConnection.getConnection();
            Statement s1 = c1.createStatement();
            ResiliencyUtils.killConnection(c1, connectionString);
            ResiliencyUtils.minimizeIdleNetworkTrackerPooledConnection(c1);
            rs = s1.executeQuery("SELECT db_name();");
            while (rs.next()) {
                resultDBName = rs.getString(1);
            }
            // Check if the driver reconnected to the expected database.
            assertEquals(originalDBName, resultDBName);
        } finally {
            TestUtils.dropDatabaseIfExists(newDBName, connectionString);
        }
    }

    @Test
    public void testPooledConnectionLang() throws SQLException {
        SQLServerConnectionPoolDataSource mds = new SQLServerConnectionPoolDataSource();
        mds.setURL(connectionString);
        PooledConnection pooledConnection = mds.getPooledConnection();
        String lang0 = null, lang1 = null;

        try (Connection c = pooledConnection.getConnection(); Statement s = c.createStatement()) {
            ResiliencyUtils.minimizeIdleNetworkTrackerPooledConnection(c);
            ResultSet rs = s.executeQuery("SELECT @@LANGUAGE;");
            while (rs.next())
                lang0 = rs.getString(1);
            s.execute("SET LANGUAGE FRENCH;");
            c.close();
            try (Connection c1 = pooledConnection.getConnection(); Statement s1 = c1.createStatement()) {
                ResiliencyUtils.killConnection(c1, connectionString);
                ResiliencyUtils.minimizeIdleNetworkTrackerPooledConnection(c1);
                rs = s1.executeQuery("SELECT @@LANGUAGE;");
                while (rs.next())
                    lang1 = rs.getString(1);
                assertEquals(lang0, lang1);
                s1.close();
                c1.close();
            } finally {
                rs.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    private void basicReconnect(String connectionString) throws SQLException {
        try (Connection c = ResiliencyUtils.getConnection(connectionString)) {
            try (Statement s = c.createStatement()) {
                ResiliencyUtils.killConnection(c, connectionString);
                s.executeQuery("SELECT 1");
            }
        }
    }
}
