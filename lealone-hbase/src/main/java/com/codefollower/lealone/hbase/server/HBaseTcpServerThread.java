/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codefollower.lealone.hbase.server;

import java.io.IOException;
import java.net.Socket;
import java.util.Properties;

import com.codefollower.lealone.constant.SysProperties;
import com.codefollower.lealone.engine.ConnectionInfo;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.hbase.engine.HBaseDatabaseEngine;
import com.codefollower.lealone.hbase.engine.HBaseSession;
import com.codefollower.lealone.server.TcpServerThread;
import com.codefollower.lealone.value.Transfer;

public class HBaseTcpServerThread extends TcpServerThread {
    private HBaseTcpServer server;

    protected HBaseTcpServerThread(Socket socket, HBaseTcpServer server, int id) {
        super(socket, server, id);
        this.server = server;
    }

    @Override
    protected Session createSession(String db, String originalURL, String userName, Transfer transfer) throws IOException {
        byte[] userPasswordHash = transfer.readBytes();
        byte[] filePasswordHash = transfer.readBytes();

        Properties originalProperties = new Properties();

        int len = transfer.readInt();
        for (int i = 0; i < len; i++) {
            originalProperties.setProperty(transfer.readString(), transfer.readString());
        }
        String baseDir = server.getBaseDir();
        if (baseDir == null) {
            baseDir = SysProperties.getBaseDir();
        }

        db = server.checkKeyAndGetDatabaseName(db);
        db = "mem:" + db; //TODO
        ConnectionInfo ci = new ConnectionInfo(db);

        if (baseDir != null) {
            ci.setBaseDir(baseDir);
        }
        if (server.getIfExists()) {
            ci.setProperty("IFEXISTS", "TRUE");
        }
        ci.setOriginalURL(originalURL);
        ci.setUserName(userName);

        ci.setUserPasswordHash(userPasswordHash);
        ci.setFilePasswordHash(filePasswordHash);
        ci.readProperties(originalProperties);

        originalProperties.setProperty("user", userName);
        if (userPasswordHash != null)
            originalProperties.put("_userPasswordHash_", userPasswordHash);
        if (filePasswordHash != null)
            originalProperties.put("_filePasswordHash_", filePasswordHash);

        if (server.getMaster() != null)
            ci.setProperty("SERVER_TYPE", "M");
        else if (server.getRegionServer() != null)
            ci.setProperty("SERVER_TYPE", "RS");
        HBaseSession session = (HBaseSession) HBaseDatabaseEngine.getInstance().createSession(ci);
        session.setMaster(server.getMaster());
        session.setRegionServer(server.getRegionServer());
        session.setOriginalProperties(originalProperties);

        return session;
    }
}
