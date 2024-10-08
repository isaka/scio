/*
 * Copyright 2019 Spotify AB.
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

package com.spotify.scio.values

import com.spotify.scio.ScioContext
import com.spotify.scio.util.Functions
import com.spotify.scio.coders.Coder
import com.twitter.algebird.{Aggregator, Monoid, MonoidAggregator, Semigroup}
import org.apache.beam.sdk.transforms.{Combine, Mean, Top}
import org.apache.beam.sdk.values.PCollection
import org.joda.time.ReadableInstant

import java.lang.{Double => JDouble, Iterable => JIterable}
import scala.jdk.CollectionConverters._

/**
 * An enhanced SCollection that uses an intermediate node to combine parts of the data to reduce
 * load on the final global combine step.
 */
class SCollectionWithFanout[T] private[values] (coll: SCollection[T], fanout: Int)
    extends PCollectionWrapper[T] {
  override val internal: PCollection[T] = coll.internal

  override val context: ScioContext = coll.context

  override def withName(name: String): this.type = {
    coll.withName(name)
    this
  }

  /** [[SCollection.aggregate[U]* SCollection.aggregate]] with fan out. */
  def aggregate[U: Coder](zeroValue: U)(seqOp: (U, T) => U, combOp: (U, U) => U): SCollection[U] =
    coll.pApply(
      Combine
        .globally(Functions.aggregateFn(context, zeroValue)(seqOp, combOp))
        .withFanout(fanout)
    )

  /** [[SCollection.aggregate[A,U]* SCollection.aggregate]] with fan out. */
  def aggregate[A: Coder, U: Coder](aggregator: Aggregator[T, A, U]): SCollection[U] = {
    val a = aggregator // defeat closure
    coll.transform { in =>
      new SCollectionWithFanout(in.map(a.prepare), fanout)
        .sum(a.semigroup)
        .map(a.present)
    }
  }

  /** [[SCollection.aggregate[A,U]* SCollection.aggregate]] with fan out. */
  def aggregate[A: Coder, U: Coder](aggregator: MonoidAggregator[T, A, U]): SCollection[U] = {
    val a = aggregator // defeat closure
    coll.transform { in =>
      new SCollectionWithFanout(in.map(a.prepare), fanout)
        .fold(a.monoid)
        .map(a.present)
    }
  }

  /** [[SCollection.combine]] with fan out. */
  def combine[C: Coder](
    createCombiner: T => C
  )(mergeValue: (C, T) => C)(mergeCombiners: (C, C) => C): SCollection[C] = {
    SCollection.logger.warn(
      "combine/sum does not support default value and may fail in some streaming scenarios. " +
        "Consider aggregate/fold instead."
    )
    coll.pApply(
      Combine
        .globally(Functions.combineFn(context, createCombiner, mergeValue, mergeCombiners))
        .withoutDefaults()
        .withFanout(fanout)
    )
  }

  /** [[SCollection.fold(zeroValue:T)* SCollection.fold]] with fan out. */
  def fold(zeroValue: T)(op: (T, T) => T): SCollection[T] =
    coll.pApply(
      Combine
        .globally(Functions.aggregateFn(context, zeroValue)(op, op))
        .withFanout(fanout)
    )

  /** [[SCollection.fold(implicit* SCollection.fold]] with fan out. */
  def fold(implicit mon: Monoid[T]): SCollection[T] =
    coll.pApply(Combine.globally(Functions.reduceFn(context, mon)).withFanout(fanout))

  /** [[SCollection.reduce]] with fan out. */
  def reduce(op: (T, T) => T): SCollection[T] =
    coll.pApply(
      Combine.globally(Functions.reduceFn(context, op)).withoutDefaults().withFanout(fanout)
    )

  /** [[SCollection.min]] with fan out. */
  def min(implicit ord: Ordering[T]): SCollection[T] =
    this.reduce(ord.min)

  /** [[SCollection.max]] with fan out. */
  def max(implicit ord: Ordering[T]): SCollection[T] =
    this.reduce(ord.max)

  /** [[SCollection.latest]] with fan out. */
  def latest: SCollection[T] = {
    coll.transform { in =>
      // widen to ReadableInstant for scala 2.12 implicit ordering
      new SCollectionWithFanout(in.withTimestamp, this.fanout)
        .max(Ordering.by(_._2: ReadableInstant))
        .keys
    }
  }

  /** [[SCollection.sum]] with fan out. */
  def sum(implicit sg: Semigroup[T]): SCollection[T] = {
    SCollection.logger.warn(
      "combine/sum does not support default value and may fail in some streaming scenarios. " +
        "Consider aggregate/fold instead."
    )
    coll.pApply(
      Combine.globally(Functions.reduceFn(context, sg)).withoutDefaults().withFanout(fanout)
    )
  }

  /** [[SCollection.mean]] with fan out. */
  def mean(implicit ev: Numeric[T]): SCollection[Double] = {
    val e = ev // defeat closure
    coll.transform { in =>
      in.map[JDouble](e.toDouble)
        .pApply(Mean.globally().withFanout(fanout))
        .asInstanceOf[SCollection[Double]]
    }
  }

  /** [[SCollection.top]] with fan out. */
  def top(num: Int)(implicit ord: Ordering[T]): SCollection[Iterable[T]] = {
    coll.transform { in =>
      in.pApply(Top.of[T, Ordering[T]](num, ord).withFanout(fanout))
        .map((l: JIterable[T]) => l.asScala)
    }
  }
}
