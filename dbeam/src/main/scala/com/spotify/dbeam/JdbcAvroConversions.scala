/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.dbeam

import java.nio.ByteBuffer
import java.sql.Types._
import java.sql._

import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.avro.{Schema, SchemaBuilder}
import org.slf4j.{Logger, LoggerFactory}

object JdbcAvroConversions {

  val log: Logger = LoggerFactory.getLogger("JdbcAvroConversions")

  val MAX_DIGITS_INT = 9
  val MAX_DIGITS_BIGINT = 19

  def normalizeForAvro(input: String): String = {
    input.replaceAll("[^A-Za-z0-9_]", "_")
  }

  def createSchemaByReadingOneRow(connection: Connection,
                                  tableName: String,
                                  avroSchemaNamespace: String): Schema = {
    log.info("Creating Avro schema based on the first read row from the database")
    try {
      val statement = connection.createStatement()
      val rs = statement.executeQuery(s"SELECT * FROM ${tableName} LIMIT 1")
      val schema = JdbcAvroConversions.createAvroSchema(
        rs, avroSchemaNamespace, connection.getMetaData.getURL)
      log.info(s"Schema created successfully. Genearated schema: ${schema.toString}")
      schema
    } finally {
      if (connection != null)
        connection.close()
    }
  }

  def createAvroSchema(rs: ResultSet,
                       avroSchemaNamesapce: String,
                       connectionUrl: String = ""): Schema = {
    val meta = rs.getMetaData
    val columnCount = meta.getColumnCount
    val tableName = if (columnCount > 0) normalizeForAvro(meta.getTableName(1)) else "no_table_name"
    val builder = SchemaBuilder.record(tableName)
      .namespace(avroSchemaNamesapce)
      .doc(s"Generate schema from JDBC ResultSet from ${tableName} ${connectionUrl}")
      .prop("tableName", tableName)
      .prop("connectionUrl", connectionUrl)
      .fields

    for (i <- 1 to columnCount) {
      val columnName: String = if (meta.getColumnName(i).isEmpty)
          meta.getColumnLabel(i) else meta.getColumnName(i)
      val normalizedColumnName: String = normalizeForAvro(columnName)
      val columnType: Int = meta.getColumnType(i)
      val typeName: String = JDBCType.valueOf(columnType).getName()
      val field = builder
        .name(normalizedColumnName)
        .doc(s"From sqlType ${columnType} ${typeName}")
        .prop("columnName", columnName)
        .prop("sqlCode", columnType.toString)
        .prop("typeName", typeName)
        .`type`.unionOf.nullBuilder.endNull.and

      columnType match {
        case CHAR | CLOB | LONGNVARCHAR | LONGVARCHAR | NCHAR | NVARCHAR | VARCHAR =>
          field.stringType.endUnion.nullDefault
        case BOOLEAN => field.booleanType.endUnion.nullDefault
        case BINARY | VARBINARY | LONGVARBINARY | ARRAY | BLOB =>
          field.bytesType.endUnion.nullDefault
        case TINYINT | SMALLINT | INTEGER => field.intType.endUnion.nullDefault
        case FLOAT | REAL => field.floatType.endUnion.nullDefault
        case DOUBLE => field.doubleType.endUnion.nullDefault
        case DECIMAL | NUMERIC => field.stringType.endUnion.nullDefault
        case DATE | TIME | TIMESTAMP | TIMESTAMP_WITH_TIMEZONE =>
          field.longBuilder.prop("logicalType", "timestamp-millis").endLong().endUnion.nullDefault

        case BIT => {
          // psql boolean, bit(1), bit(3), bit(n) are all sqlCode=BIT
          // check precision to distinguish boolean to bytes casting
          val precision = meta.getPrecision(i)
          if (precision <= 1) {
            field.booleanType.endUnion.nullDefault
          } else {
            field.bytesType.endUnion.nullDefault
          }
        }
        case BIGINT =>
          val precision = meta.getPrecision(i)
          if (precision > 0 && precision <= MAX_DIGITS_BIGINT) {
            field.longType.endUnion.nullDefault
          } else {
            field.stringType.endUnion.nullDefault
          }

        case _ => field.stringType.endUnion.nullDefault
      }
    }
    builder.endRecord()
  }

  def nullableBytes(bts: scala.Array[Byte]): ByteBuffer = {
    if (bts != null) {
      ByteBuffer.wrap(bts)
    } else {
      null
    }
  }

  /**
    * Fetch resultSet data and convert to Java Objects
    * org.postgresql.jdbc.TypeInfoCache
    * com.mysql.jdbc.MysqlDefs#mysqlToJavaType(int)
    */
  def convertFieldToType(r: ResultSet, i: Integer, meta: ResultSetMetaData) : Any = {
    val ret: Any = meta.getColumnType(i) match {
      case CHAR | CLOB | LONGNVARCHAR | LONGVARCHAR | NCHAR | NVARCHAR | VARCHAR => r.getString(i)
      case BOOLEAN => r.getBoolean(i)
      case BINARY | VARBINARY | LONGVARBINARY | ARRAY | BLOB => nullableBytes(r.getBytes(i))
      case TINYINT | SMALLINT | INTEGER => r.getInt(i)
      case FLOAT | REAL => r.getFloat(i)
      case DOUBLE => r.getDouble(i)
      case DATE | TIME | TIMESTAMP | TIMESTAMP_WITH_TIMEZONE => {
        val t: Timestamp = r.getTimestamp(i)
        if (t != null) {
          t.getTime
        } else {
          t
        }
      }
      case BIT => {
        val precision = meta.getPrecision(i)
        if (precision <= 1) {
          r.getBoolean(i)
        } else {
          nullableBytes(r.getBytes(i))
        }
      }
      case BIGINT =>
        val precision = meta.getPrecision(i)
        if (precision > 0 && precision <= MAX_DIGITS_BIGINT) {
          r.getLong(i)
        } else {
          r.getString(i)
        }

      case _ => r.getString(i)
    }
    if (r.wasNull()) {
      null
    } else {
      ret
    }
  }

  def convertResultSetIntoAvroRecord(schema: Schema, r: ResultSet): GenericRecord = {
    val rec: GenericRecord = new GenericData.Record(schema)
    val meta: ResultSetMetaData = r.getMetaData

    for (i <- 1 to meta.getColumnCount) {
      val value: Any = convertFieldToType(r, i, meta)
      if (value != null) {
        rec.put(i - 1, value)
      }
    }

    rec
  }
}