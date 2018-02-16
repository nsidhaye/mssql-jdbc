package com.microsoft.sqlserver.jdbc;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * This will generate SQLServerConnection depend on runtime.
 */
class SQLServerConnectionFactory {

    private static final String CONNECTION_FOR_JDK_9 = "com.microsoft.sqlserver.jdbc.SQLServerConnection43";

    static final private java.util.logging.Logger parentLogger = java.util.logging.Logger.getLogger("com.microsoft.sqlserver.jdbc");

    /**
     *
     * @param traceId ParentInfo / Internal tracking String / id
     * @return SQLServerConnection
     * @throws SQLServerException
     */
    public static SQLServerConnection getSQLServerConnection(String traceId) throws SQLServerException {
        SQLServerConnection connection = null;

        if (Util.use43Wrapper()) {
            ClassLoader classLoader = ClassLoader.getSystemClassLoader(); //SQLServerConnectionFactory.class.getClassLoader();

            try {
                Class jdk9Class = classLoader.loadClass(CONNECTION_FOR_JDK_9);
                Constructor con = jdk9Class.getConstructor(String.class);
                connection = (SQLServerConnection)con.newInstance(traceId);
//                connection = (SQLServerConnection) jdk9Class.newInstance();
            }catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
                parentLogger.severe("Could not load SQLServerConnection43 class");

                throw new SQLServerException("Error while creating SQL Server Connection", e);
            }
        }else {
            connection = new SQLServerConnection(traceId);
        }


        return connection;
    }

}
