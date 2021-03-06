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
package com.codefollower.lealone.test.benchmark;

import java.sql.ResultSet;

import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.coprocessor.AggregationClient;
import org.apache.hadoop.hbase.client.coprocessor.LongColumnInterpreter;

public class BenchAggregation extends BenchWrite {
    public static void main(String[] args) throws Exception {
        new BenchAggregation(1000000, 2000000).run();
    }

    private AggregationClient ac;
    private byte[] tableNameAsBytes;
    private LongColumnInterpreter ci;
    private Scan scan;

    public BenchAggregation(int startKey, int endKey) {
        super("BenchAggregation", startKey, endKey);
        loop = 2;
    }

    @Override
    public void createTable() throws Exception {
        StringBuilder s = new StringBuilder();
        for (int i = startKey; i < endKey; i += 100000) {
            if (i != startKey)
                s.append(',');
            s.append("'RK").append(i).append("'");
        }

        stmt.executeUpdate("CREATE HBASE TABLE IF NOT EXISTS " + tableName + " (" //
                + "SPLIT KEYS(" + s + "), " //预分region
                + "COLUMN FAMILY cf(id int, name varchar(500), age long, salary double))");
    }

    public void run() throws Exception {
        tableNameAsBytes = b(tableName.toUpperCase());
        ci = new LongColumnInterpreter();
        scan = new Scan();
        scan.addFamily(b("CF"));

        init();
        createTable();
        initHTable();
        ac = new AggregationClient(conf);

        ResultSet rs = stmt.executeQuery("select * from " + tableName + " where _rowkey_='RK" + startKey + "'");
        if (!rs.next())
            testHBaseBatch();

        for (int i = 0; i < loop; i++) {
            total += testCount();
        }
        avg();

        stmt.setFetchSize(10000);
        for (int i = 0; i < loop; i++) {
            total += testCount();
        }
        avg();

        stmt.setFetchSize(500);
        for (int i = 0; i < loop; i++) {
            total += testCount();
        }
        avg();

        for (int i = 0; i < loop; i++) {
            total += testHBaseCount();
        }
        avg();
    }

    long testCount() throws Exception {
        String sql = "select count(*) from " + tableName;
        sql = "select count(*) from " + tableName + " where _rowkey_>='RK1900000'";

        long start = System.nanoTime();
        ResultSet r = stmt.executeQuery(sql);
        long end = System.nanoTime();
        p("testCount()", end - start);
        r.next();
        p("rowCount=" + r.getInt(1));
        r.close();
        return end - start;
    }

    long testHBaseCount() throws Exception {
        long start = System.nanoTime();
        long rowCount = 0;
        scan.setStartRow(b("RK1900000"));
        try {
            rowCount = ac.rowCount(tableNameAsBytes, ci, scan);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        long end = System.nanoTime();
        p("testHBaseCount()", end - start);
        p("rowCount=" + rowCount);

        return end - start;
    }
}
