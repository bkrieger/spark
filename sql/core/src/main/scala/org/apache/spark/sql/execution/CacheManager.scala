/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.collection.JavaConverters._

import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.catalyst.expressions.SubqueryExpression
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.columnar.InMemoryRelation
import org.apache.spark.sql.execution.datasources.{HadoopFsRelation, LogicalRelation}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK

/** Holds a cached logical plan and its data */
case class CachedData(plan: LogicalPlan, cachedRepresentation: InMemoryRelation)

/**
 * Provides support in a SQLContext for caching query results and automatically using these cached
 * results when subsequent queries are executed.  Data is cached using byte buffers stored in an
 * InMemoryRelation.  This relation is automatically substituted query plans that return the
 * `sameResult` as the originally cached query.
 *
 * Internal to Spark SQL.
 */
class CacheManager extends Logging {

  @transient
  private val cachedData = new java.util.LinkedList[CachedData]

  @transient
  private val cacheLock = new ReentrantReadWriteLock

  /** Acquires a read lock on the cache for the duration of `f`. */
  private def readLock[A](f: => A): A = {
    val lock = cacheLock.readLock()
    lock.lock()
    try f finally {
      lock.unlock()
    }
  }

  /** Acquires a write lock on the cache for the duration of `f`. */
  private def writeLock[A](f: => A): A = {
    val lock = cacheLock.writeLock()
    lock.lock()
    try f finally {
      lock.unlock()
    }
  }

  /** Clears all cached tables. */
  def clearCache(): Unit = writeLock {
    cachedData.asScala.foreach(_.cachedRepresentation.cachedColumnBuffers.unpersist())
    cachedData.clear()
  }

  /** Checks if the cache is empty. */
  def isEmpty: Boolean = readLock {
    cachedData.isEmpty
  }

  /**
   * Caches the data produced by the logical representation of the given [[Dataset]].
   * Unlike `RDD.cache()`, the default storage level is set to be `MEMORY_AND_DISK` because
   * recomputing the in-memory columnar representation of the underlying table is expensive.
   */
  def cacheQuery(
      query: Dataset[_],
      tableName: Option[String] = None,
      storageLevel: StorageLevel = MEMORY_AND_DISK): Unit = writeLock {
    val planToCache = query.logicalPlan
    if (lookupCachedData(planToCache).nonEmpty) {
      logWarning("Asked to cache already cached data.")
    } else {
      val sparkSession = query.sparkSession
      cachedData.add(CachedData(
        planToCache,
        InMemoryRelation(
          sparkSession.sessionState.conf.useCompression,
          sparkSession.sessionState.conf.columnBatchSize,
          storageLevel,
          sparkSession.sessionState.executePlan(planToCache).executedPlan,
          tableName)))
    }
  }

  /**
   * Un-cache all the cache entries that refer to the given plan.
   */
  def uncacheQuery(query: Dataset[_], blocking: Boolean = true): Unit = writeLock {
    uncacheQuery(query.sparkSession, query.logicalPlan, blocking)
  }

  /**
   * Un-cache all the cache entries that refer to the given plan.
   */
  def uncacheQuery(spark: SparkSession, plan: LogicalPlan, blocking: Boolean): Unit = writeLock {
    val it = cachedData.iterator()
    while (it.hasNext) {
      val cd = it.next()
      if (cd.plan.sameResult(plan)) {
        cd.cachedRepresentation.cachedColumnBuffers.unpersist(blocking)
        it.remove()
      }
    }
  }

  /**
   * Tries to re-cache all the cache entries that refer to the given plan.
   */
  def recacheByPlan(spark: SparkSession, plan: LogicalPlan): Unit = writeLock {
    recacheByCondition(spark, _.find(_.sameResult(plan)).isDefined)
  }

  private def recacheByCondition(spark: SparkSession, condition: LogicalPlan => Boolean): Unit = {
    val it = cachedData.iterator()
    val needToRecache = scala.collection.mutable.ArrayBuffer.empty[CachedData]
    while (it.hasNext) {
      val cd = it.next()
      if (condition(cd.plan)) {
        cd.cachedRepresentation.cachedColumnBuffers.unpersist()
        // Remove the cache entry before we create a new one, so that we can have a different
        // physical plan.
        it.remove()
        val newCache = InMemoryRelation(
          useCompression = cd.cachedRepresentation.useCompression,
          batchSize = cd.cachedRepresentation.batchSize,
          storageLevel = cd.cachedRepresentation.storageLevel,
          child = spark.sessionState.executePlan(cd.plan).executedPlan,
          tableName = cd.cachedRepresentation.tableName)
        needToRecache += cd.copy(cachedRepresentation = newCache)
      }
    }

    needToRecache.foreach(cachedData.add)
  }

  /** Optionally returns cached data for the given [[Dataset]] */
  def lookupCachedData(query: Dataset[_]): Option[CachedData] = readLock {
    lookupCachedData(query.logicalPlan)
  }

  /** Optionally returns cached data for the given [[LogicalPlan]]. */
  def lookupCachedData(plan: LogicalPlan): Option[CachedData] = readLock {
    cachedData.asScala.find(cd => plan.sameResult(cd.plan))
  }

  /** Replaces segments of the given logical plan with cached versions where possible. */
  def useCachedData(plan: LogicalPlan): LogicalPlan = {
    val newPlan = plan transformDown {
      case currentFragment =>
        lookupCachedData(currentFragment)
          .map(_.cachedRepresentation.withOutput(currentFragment.output))
          .getOrElse(currentFragment)
    }

    newPlan transformAllExpressions {
      case s: SubqueryExpression => s.withNewPlan(useCachedData(s.plan))
    }
  }

  /**
   * Tries to re-cache all the cache entries that contain `resourcePath` in one or more
   * `HadoopFsRelation` node(s) as part of its logical plan.
   */
  def recacheByPath(spark: SparkSession, resourcePath: String): Unit = writeLock {
    val (fs, qualifiedPath) = {
      val path = new Path(resourcePath)
      val fs = path.getFileSystem(spark.sessionState.newHadoopConf())
      (fs, fs.makeQualified(path))
    }

    recacheByCondition(spark, _.find(lookupAndRefresh(_, fs, qualifiedPath)).isDefined)
  }

  /**
   * Traverses a given `plan` and searches for the occurrences of `qualifiedPath` in the
   * [[org.apache.spark.sql.execution.datasources.FileIndex]] of any [[HadoopFsRelation]] nodes
   * in the plan. If found, we refresh the metadata and return true. Otherwise, this method returns
   * false.
   */
  private def lookupAndRefresh(plan: LogicalPlan, fs: FileSystem, qualifiedPath: Path): Boolean = {
    plan match {
      case lr: LogicalRelation => lr.relation match {
        case hr: HadoopFsRelation =>
          val prefixToInvalidate = qualifiedPath.toString
          val invalidate = hr.location.rootPaths
            .map(_.makeQualified(fs.getUri, fs.getWorkingDirectory).toString)
            .exists(_.startsWith(prefixToInvalidate))
          if (invalidate) hr.location.refresh()
          invalidate
        case _ => false
      }
      case _ => false
    }
  }
}
