/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.backend.store.rocksdb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.List;

import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.id.IdGenerator;
import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.Condition.Relation;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.backend.store.BackendEntry.BackendColumn;
import com.baidu.hugegraph.backend.store.rocksdb.RocksDBSessions.Session;
import com.baidu.hugegraph.structure.HugeIndex;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.util.E;

public class RocksDBTables {

    public static class Counters extends RocksDBTable {

        public static final String TABLE = "c";

        public static final int MAX_TIMES = 1000;
        public static final byte[] ONE = b(1L);

        public Counters(String database) {
            super(database, TABLE);
        }

        public synchronized Id nextId(Session session, HugeType type) {
            byte[] key = new byte[]{type.code()};

            // Do get-increase-get-compare operation
            long counter = 0L;
            long expect = -1L;
            for (int i = 0; i < MAX_TIMES; i++) {
                // Get the latest value
                byte[] value = session.get(this.table(), key);
                if (value != null) {
                    counter = l(value);
                }
                if (counter == expect) {
                    break;
                }
                // Increase local counter
                expect = counter + 1;
                // Increase 1, the default value of counter is 0 in RocksDB
                session.merge(this.table(), key, ONE);
                session.commit();
            }

            E.checkState(counter != 0, "Please check whether RocksDB is OK");
            E.checkState(counter == expect, "RocksDB is busy please try again");
            return IdGenerator.of(counter);
        }

        private static byte[] b(long value) {
            return ByteBuffer.allocate(Long.BYTES)
                             .order(ByteOrder.nativeOrder())
                             .putLong(value).array();
        }

        private static long l(byte[] bytes) {
            assert bytes.length == Long.BYTES;
            return ByteBuffer.wrap(bytes)
                             .order(ByteOrder.nativeOrder())
                             .getLong();
        }
    }

    public static class VertexLabel extends RocksDBTable {

        public static final String TABLE = "vl";

        public VertexLabel(String database) {
            super(database, TABLE);
        }
    }

    public static class EdgeLabel extends RocksDBTable {

        public static final String TABLE = "el";

        public EdgeLabel(String database) {
            super(database, TABLE);
        }
    }

    public static class PropertyKey extends RocksDBTable {

        public static final String TABLE = "pk";

        public PropertyKey(String database) {
            super(database, TABLE);
        }
    }

    public static class IndexLabel extends RocksDBTable {

        public static final String TABLE = "il";

        public IndexLabel(String database) {
            super(database, TABLE);
        }
    }

    public static class Vertex extends RocksDBTable {

        public static final String TABLE = "v";

        public Vertex(String database) {
            super(database, TABLE);
        }
    }

    public static class Edge extends RocksDBTable {

        public static final String TABLE = "e";

        public Edge(String database) {
            super(database, TABLE);
        }
    }

    public static class SecondaryIndex extends RocksDBTable {

        public static final String TABLE = "si";

        public SecondaryIndex(String database) {
            super(database, TABLE);
        }

        @Override
        protected Iterator<BackendEntry> queryByCond(Session session,
                                                     ConditionQuery query) {
            E.checkArgument(query.allSysprop() &&
                            query.conditions().size() == 2,
                            "There should be two conditions: " +
                            "INDEX_LABEL_ID and FIELD_VALUES" +
                            "in secondary index query");

            Id index = (Id) query.condition(HugeKeys.INDEX_LABEL_ID);
            Object key = query.condition(HugeKeys.FIELD_VALUES);

            E.checkArgument(index != null, "Please specify the index label");
            E.checkArgument(key != null, "Please specify the index key");

            Id id = HugeIndex.formatIndexId(query.resultType(), index, key);
            return newEntryIterator(this.queryById(session, id), query);
        }
    }

    // TODO: change to RangeIndex
    public static class SearchIndex extends RocksDBTable {

        public static final String TABLE = "ri";

        public SearchIndex(String database) {
            super(database, TABLE);
        }

        @Override
        protected Iterator<BackendEntry> queryByCond(Session session,
                                                     ConditionQuery query) {
            assert !query.conditions().isEmpty();

            Id index = (Id) query.condition(HugeKeys.INDEX_LABEL_ID);
            Object key = query.condition(HugeKeys.FIELD_VALUES);
            E.checkArgument(index != null, "Please specify the index label");

            List<? extends Relation> relations = null;

            if (key != null) {
                final String msg = "Expect one relation in search query";
                E.checkArgument(query.conditions().size() == 2, msg);
                for (Condition c : query.conditions()) {
                    if (c.isRelation()) {
                        key = ((Condition.Relation) c).key();
                        if (key.equals(HugeKeys.FIELD_VALUES)) {
                            relations = c.relations();
                            break;
                        }
                    }
                }
            } else {
                // TODO: query by range, like: 18 < age and age < 20
                final String msg = "Expect one AND condition in search query";
                E.checkArgument(query.conditions().size() == 2, msg);
                Condition.And and = null;
                for (Condition c : query.conditions()) {
                    if (c instanceof Condition.And) {
                        and = (Condition.And) c;
                        break;
                    }
                }
                E.checkArgument(and != null, msg);
                E.checkArgument(and.left().isRelation() &&
                                and.right().isRelation(),
                                "Expect relations in AND condition");

                relations = and.relations();
                E.checkArgument(relations.size() == 2,
                                "Expect 2 relations in AND condition");
            }

            E.checkArgument(relations != null,
                            "Expect relations in search query");

            Object keyEq = null;
            Object keyMin = null;
            boolean keyMinEq = false;
            Object keyMax = null;
            boolean keyMaxEq = false;

            for (Relation r : relations) {
                E.checkArgument(r.key() == HugeKeys.FIELD_VALUES,
                                "Expect FIELD_VALUES in AND condition");
                switch (r.relation()) {
                    case EQ:
                        keyEq = r.value();
                        break;
                    case GTE:
                        keyMinEq = true;
                    case GT:
                        keyMin = r.value();
                        break;
                    case LTE:
                        keyMaxEq = true;
                    case LT:
                        keyMax = r.value();
                        break;
                    default:
                        E.checkArgument(false, "Unsupported relation '%s'",
                                        r.relation());
                }
            }

            HugeType type = query.resultType();
            Iterator<BackendColumn> itor;
            if (keyEq != null) {
                Id id = HugeIndex.formatIndexId(type, index, keyEq);
                itor = queryById(session, id);
            } else {
                if (keyMin == null) {
                    keyMin = 0L;
                    keyMinEq = true;
                }

                Id min = HugeIndex.formatIndexId(type, index, keyMin);
                byte[] begin = min.asBytes();
                if (!keyMinEq) {
                    begin = RocksDBSessions.increase(begin);
                }

                if (keyMax == null) {
                    itor = session.scan(table(), begin, null);
                } else {
                    Id max = HugeIndex.formatIndexId(type, index, keyMax);
                    byte[] end = max.asBytes();
                    if (keyMaxEq) {
                        end = RocksDBSessions.increase(end);
                    }
                    itor = session.scan(table(), begin, end);
                }
            }
            return newEntryIterator(itor, query);
        }
    }
}