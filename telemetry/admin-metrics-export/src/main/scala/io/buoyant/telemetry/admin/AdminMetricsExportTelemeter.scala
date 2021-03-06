package io.buoyant.telemetry.admin

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.twitter.app.GlobalFlag
import com.twitter.conversions.time._
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.Service
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.telemetry.Metric.{Counter, Gauge, HistogramSummary, Stat}
import io.buoyant.telemetry.{Metric, MetricsTree, Telemeter}
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

/**
 * AdminMetricsExportTelemeter provides the /admin/metrics.json admin endpoint, backed by a
 * MetricsTree.  It does not provide a StatsReciever or Tracer.  Histograms are snapshotted into
 * summaries at a regular interval and the values served on /admin/metrics.json are taken from the
 * most recent summary snapshot.  Counter and gauge values are served live.
 */
class AdminMetricsExportTelemeter(
  metrics: MetricsTree,
  snapshotInterval: Duration,
  timer: Timer
) extends Telemeter with Admin.WithHandlers {

  private[admin] val handler = Service.mk { request: Request =>
    val pretty = request.getBooleanParam("pretty", false)
    val response = request.response
    response.mediaType = MediaType.Json
    response.withOutputStream(writeJson(_, pretty))
    Future.value(response)
  }

  val adminHandlers: Seq[Admin.Handler] = Seq(
    Admin.Handler("/admin/metrics.json", handler)
  )

  val stats = NullStatsReceiver
  def tracer = NullTracer

  private[this] val started = new AtomicBoolean(false)
  // only run at most once
  def run(): Closable with Awaitable[Unit] =
    if (started.compareAndSet(false, true)) run0()
    else Telemeter.nopRun

  private[this] def run0() = {
    val task = timer.schedule(snapshotInterval) {
      snapshotHistograms(metrics)
    }

    new Closable with CloseAwaitably {
      override def close(deadline: Time): Future[Unit] = closeAwaitably(task.close(deadline))
    }
  }

  private[this] val json = new JsonFactory()
  private[this] def writeJson(out: OutputStream, pretty: Boolean = false): Unit = {
    val jg = json.createGenerator(out)
    if (pretty) jg.setPrettyPrinter(new DefaultPrettyPrinter())
    jg.writeStartObject()
    val flattened =
      if (pretty) flattenMetricsTree(metrics).sortBy(_._1)
      else flattenMetricsTree(metrics)
    flattened.foreach {
      case (name, c: Counter) =>
        jg.writeNumberField(name, c.get)
      case (name, g: Gauge) =>
        jg.writeNumberField(name, g.get)
      case (name, s: Stat) =>
        for (summary <- Option(s.snapshottedSummary)) {
          jg.writeNumberField(s"$name.count", summary.count)
          if (summary.count > 0) {
            jg.writeNumberField(s"$name.max", summary.max)
            jg.writeNumberField(s"$name.min", summary.min)
            jg.writeNumberField(s"$name.p50", summary.p50)
            jg.writeNumberField(s"$name.p90", summary.p90)
            jg.writeNumberField(s"$name.p95", summary.p95)
            jg.writeNumberField(s"$name.p99", summary.p99)
            jg.writeNumberField(s"$name.p9990", summary.p9990)
            jg.writeNumberField(s"$name.p9999", summary.p9999)
            jg.writeNumberField(s"$name.sum", summary.sum)
            jg.writeNumberField(s"$name.avg", summary.avg)
          }
        }
      case (_, Metric.None) =>
    }
    jg.writeEndObject()
    jg.close()
  }

  private[this] def flattenMetricsTree(
    tree: MetricsTree,
    prefix: String = "",
    acc: mutable.Buffer[(String, Metric)] = mutable.Buffer()
  ): Seq[(String, Metric)] = {
    acc += (prefix -> tree.metric)
    for ((name, child) <- tree.children) {
      if (prefix.isEmpty)
        flattenMetricsTree(child, name, acc)
      else
        flattenMetricsTree(child, s"$prefix/$name", acc)
    }
    acc.toSeq
  }

  /** Snapshot histograms to produce histogram summaries, resetting as we go. */
  private[this] def snapshotHistograms(tree: MetricsTree): Unit = {
    tree.metric match {
      case stat: Stat =>
        stat.snapshot()
        stat.reset()
      case _ => None
    }
    for (child <- tree.children.values) snapshotHistograms(child)
  }

}

object histogramSnapshotInterval extends GlobalFlag(1.minute, "Interval to snapshot histrograms")
