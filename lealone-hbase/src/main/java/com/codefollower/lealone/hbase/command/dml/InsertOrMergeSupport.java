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
package com.codefollower.lealone.hbase.command.dml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hbase.client.Put;

import com.codefollower.lealone.command.CommandInterface;
import com.codefollower.lealone.command.CommandRemote;
import com.codefollower.lealone.command.dml.Query;
import com.codefollower.lealone.dbobject.table.Column;
import com.codefollower.lealone.dbobject.table.Table;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.engine.SessionInterface;
import com.codefollower.lealone.expression.Expression;
import com.codefollower.lealone.expression.Parameter;
import com.codefollower.lealone.hbase.command.CommandParallel;
import com.codefollower.lealone.hbase.dbobject.table.HBaseTable;
import com.codefollower.lealone.hbase.engine.HBaseSession;
import com.codefollower.lealone.hbase.engine.SessionRemotePool;
import com.codefollower.lealone.hbase.result.HBaseRow;
import com.codefollower.lealone.hbase.util.HBaseRegionInfo;
import com.codefollower.lealone.hbase.util.HBaseUtils;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.result.Row;
import com.codefollower.lealone.util.New;
import com.codefollower.lealone.util.StatementBuilder;
import com.codefollower.lealone.util.StringUtils;
import com.codefollower.lealone.value.Value;
import com.codefollower.lealone.value.ValueNull;
import com.codefollower.lealone.value.ValueString;
import com.codefollower.lealone.value.ValueUuid;

public class InsertOrMergeSupport {
    private final Map<String, Map<String, List<String>>> servers = New.hashMap();
    private final HBaseSession session;
    private final InsertOrMerge iom;
    private final boolean isInsert;

    private StatementBuilder alterTable;
    private ArrayList<Column> alterColumns;
    private boolean isBatch = false;
    private int rowKeyColumnIndex = -1;

    private HBaseTable table;
    private ArrayList<Expression[]> list;
    private Column[] columns;
    private Column[] keys;

    public InsertOrMergeSupport(Session session, InsertOrMerge iom, boolean isInsert) {
        this.session = (HBaseSession) session;
        this.iom = iom;
        this.isInsert = isInsert;
    }

    public void postPrepare(Table table, Query query, ArrayList<Expression[]> list, Column[] columns, Column[] keys) {
        this.table = (HBaseTable) table;
        this.list = list;
        this.columns = columns;
        this.keys = keys;
        if (session.getAutoCommit() && (query != null || list.size() > 1)) {
            session.setAutoCommit(false);
            isBatch = true;
        }

        if (query != null) {
            int index = -1;
            for (Column c : columns) {
                index++;
                if (c.isRowKeyColumn()) {
                    rowKeyColumnIndex = index;
                    break;
                }
            }
        }
    }

    public int update(boolean insertFromSelect, boolean sortedInsertMode, ArrayList<Parameter> parameters) {
        //当在Parser中解析insert语句时，如果insert中的一些字段是新的，那么会标注字段列表已修改了，
        //并且新字段的类型是未知的，只有在执行insert时再由字段值的实际类型确定字段的类型。
        if (table.isColumnsModified()) {
            alterTable = new StatementBuilder("ALTER TABLE ");
            //不能使用ALTER TABLE t ADD COLUMN(f1 int, f2 int)这样的语法，因为有可能多个RS都在执行这种语句，在Master那会有冲突
            alterTable.append(table.getSQL()).append(" ADD COLUMN IF NOT EXISTS ");

            alterColumns = New.arrayList();
        }
        int updateCount;
        try {
            updateCount = iom.internalUpdate();

            if (!servers.isEmpty()) {
                List<CommandInterface> commands = New.arrayList(servers.size());
                for (Map.Entry<String, Map<String, List<String>>> e : servers.entrySet()) {
                    CommandRemote c = SessionRemotePool.getCommandRemote(session, parameters, e.getKey(), //
                            getPlanSQL(insertFromSelect, sortedInsertMode, e.getValue().entrySet()));

                    commands.add(c);
                }

                updateCount += CommandParallel.executeUpdate(commands);
            }

            if (table.isColumnsModified()) {
                table.setColumnsModified(false);
                SessionInterface si = SessionRemotePool.getMasterSessionRemote(session.getOriginalProperties());
                for (Column c : alterColumns) {
                    CommandInterface ci = si.prepareCommand(alterTable + c.getCreateSQL(true), 1);
                    ci.executeUpdate();
                }
                si.close();
            }
            if (isBatch)
                session.commit(false);
            return updateCount;
        } catch (Exception e) {
            if (isBatch)
                session.rollback();
            throw DbException.convert(e);
        } finally {
            if (isBatch)
                session.setAutoCommit(true);
            servers.clear();
        }
    }

    private String getPlanSQL(boolean insertFromSelect, boolean sortedInsertMode, Set<Map.Entry<String, List<String>>> regions) {
        StatementBuilder buff = new StatementBuilder();
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : regions) {
            if (!first) {
                buff.append(";");
                buff.append('\n');
            } else {
                first = false;
            }
            buff.append("IN THE REGION ");
            buff.append(StringUtils.quoteStringSQL(entry.getKey()));
            buff.append('\n');
            if (isInsert)
                buff.append("INSERT INTO ");
            else
                buff.append("MERGE INTO ");
            buff.append(table.getSQL()).append('(');
            buff.resetCount();
            for (Column c : columns) {
                buff.appendExceptFirst(", ");
                buff.append(c.getSQL());
            }
            buff.append(")\n");

            if (isInsert) {
                if (insertFromSelect) {
                    buff.append("DIRECT ");
                }
                if (sortedInsertMode) {
                    buff.append("SORTED ");
                }
            } else {
                if (keys != null) {
                    buff.append(" KEY(");
                    buff.resetCount();
                    for (Column c : keys) {
                        buff.appendExceptFirst(", ");
                        buff.append(c.getSQL());
                    }
                    buff.append(")\n");
                }
            }
            buff.append("VALUES ");
            int row = 0;
            if (entry.getValue().size() > 1) {
                buff.append('\n');
            }
            for (String value : entry.getValue()) {
                if (row++ > 0) {
                    buff.append(",\n");
                }
                buff.append(value);
            }
        }
        return buff.toString();
    }

    private static String getPlanSQL(Value[] values) {
        StatementBuilder buff = new StatementBuilder();
        buff.append('(');
        for (Value v : values) {
            buff.appendExceptFirst(", ");
            if (v == null) {
                buff.append("NULL");
            } else {
                buff.append(v.getSQL());
            }
        }
        buff.append(')');
        return buff.toString();
    }

    private static String getPlanSQL(Expression[] list) {
        StatementBuilder buff = new StatementBuilder();
        buff.append('(');
        for (Expression e : list) {
            buff.appendExceptFirst(", ");
            if (e == null) {
                buff.append("DEFAULT");
            } else {
                buff.append(e.getSQL());
            }
        }
        buff.append(')');
        return buff.toString();
    }

    private HBaseRow createRow(Value rowKey, String value) {
        rowKey = ValueString.get(rowKey.getString());
        byte[] rowKeyAsBytes = HBaseUtils.toBytes(rowKey);

        HBaseRegionInfo hri = HBaseUtils.getHBaseRegionInfo(getTableNameAsBytes(), rowKeyAsBytes);
        if (!HBaseUtils.isLocal(session, hri)) {
            Map<String, List<String>> regions = servers.get(hri.getRegionServerURL());
            if (regions == null) {
                regions = New.hashMap();
                servers.put(hri.getRegionServerURL(), regions);
            }

            List<String> values = regions.get(hri.getRegionName());
            if (values == null) {
                values = New.arrayList();
                regions.put(hri.getRegionName(), values);
            }
            values.add(value);

            return null;
        }

        HBaseRow row = (HBaseRow) table.getTemplateRow();
        row.setRowKey(rowKey);
        row.setRegionName(hri.getRegionNameAsBytes());

        Put put;
        if (session.getTransaction() != null)
            put = new Put(HBaseUtils.toBytes(rowKey), session.getTransaction().getTransactionId());
        else
            put = new Put(HBaseUtils.toBytes(rowKey));
        row.setPut(put);

        return row;
    }

    protected Row createRow(Expression[] expr, int rowId) {
        HBaseRow row = createRow(getRowKey(rowId), getPlanSQL(expr));
        if (row == null)
            return null;

        Put put = row.getPut();
        Column c;
        Value v;
        Expression e;
        for (int i = 0, len = columns.length; i < len; i++) {
            c = columns[i];
            if (!((HBaseTable) this.table).isStatic() && c.isRowKeyColumn())
                continue;
            e = expr[i];
            if (e != null) {
                // e can be null (DEFAULT)
                e = e.optimize(session);
                v = e.getValue(session);
                if (c.isTypeUnknown()) {
                    c.setType(v.getType());
                    //alterTable.appendExceptFirst(", ");
                    //alterTable.append(c.getCreateSQL());
                    if (alterColumns != null)
                        alterColumns.add(c);
                }
                v = c.convert(e.getValue(session));
                row.setValue(c.getColumnId(), v);

                put.add(c.getColumnFamilyNameAsBytes(), c.getNameAsBytes(), HBaseUtils.toBytes(v));
            } else {
                put.add(c.getColumnFamilyNameAsBytes(), c.getNameAsBytes(), HBaseUtils.toBytes(ValueNull.INSTANCE));
            }
        }
        return row;
    }

    protected Row createRow(Value[] values) {
        HBaseRow row = createRow(getRowKey(values), getPlanSQL(values));
        if (row == null)
            return null;

        Put put = row.getPut();
        Column c;
        Value v;

        for (int j = 0, len = columns.length; j < len; j++) {
            c = columns[j];
            if (!((HBaseTable) this.table).isStatic() && c.isRowKeyColumn())
                continue;
            int index = c.getColumnId();
            v = values[j];
            if (c.isTypeUnknown()) {
                c.setType(v.getType());
                if (alterColumns != null)
                    alterColumns.add(c);
            }
            v = c.convert(values[j]);
            row.setValue(index, v);

            put.add(c.getColumnFamilyNameAsBytes(), c.getNameAsBytes(), HBaseUtils.toBytes(v));
        }

        return row;
    }

    private byte[] getTableNameAsBytes() {
        return ((HBaseTable) table).getTableNameAsBytes();
    }

    private Value getRowKey(Value[] values) {
        if (rowKeyColumnIndex == -1)
            return getRowKey();
        else
            return values[rowKeyColumnIndex];
    }

    private Value getRowKey(int rowIndex) {
        if (!list.isEmpty() && list.get(rowIndex).length > 0) {
            int columnIndex = 0;
            for (Column c : columns) {
                if (c.isRowKeyColumn()) {
                    return list.get(rowIndex)[columnIndex].getValue(session);
                }
                columnIndex++;
            }
        }
        return getRowKey();
    }

    private Value getRowKey() {
        if (table.isStatic())
            return ValueUuid.getNewRandom();
        else
            throw new RuntimeException("do not find rowKey field");
    }
}
