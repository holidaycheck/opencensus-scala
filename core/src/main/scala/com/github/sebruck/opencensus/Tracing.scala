package com.github.sebruck.opencensus

import com.typesafe.scalalogging.LazyLogging
import io.opencensus.trace.samplers.Samplers
import pureconfig.loadConfigOrThrow
import io.opencensus.trace.{
  EndSpanOptions,
  Span,
  SpanBuilder,
  Status,
  Tracing => OpencensusTracing
}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait Tracing {
  private val tracer       = OpencensusTracing.getTracer
  private val unknownError = (_: Throwable) => Status.UNKNOWN
  protected def config: Config

  /**
    * Starts a root span
    */
  def startSpan(name: String): Span = buildSpan(tracer.spanBuilder(name))

  /**
    * Starts a child span of the given parent
    */
  def startChildSpan(name: String, parent: Span): Span =
    buildSpan(tracer.spanBuilderWithExplicitParent(name, parent))

  /**
    * Ends the span with the given status
    */
  def endSpan(span: Span, status: Status): Unit =
    span.end(EndSpanOptions.builder().setStatus(status).build())

  /**
    * Starts a new root span before executing the given function.
    *
    * When the Future which is returned by the provided function completes successfully, the span will be ended
    * with the status returned by `successStatus` otherwise with the status returned by `failureStatus`.
    *
    * @param name the name of the created span
    * @param failureStatus function defining the status with which the Span will be ended in case of failure
    * @param f an unary function which parameter is the action which should be traced. The newly created span is given
    *         as a parameter in case it is needed as parent reference for further spans.
    * @return the return value of f
    */
  def trace[T](name: String, failureStatus: Throwable => Status = unknownError)(
      f: Span => Future[T])(implicit ec: ExecutionContext): Future[T] =
    traceSpan(startSpan(name), failureStatus)(f)

  /**
    * Starts a new child span of the given parent span before executing the given function.
    *
    * When the Future which is returned by the provided function completes successfully, the span will be ended
    * with the status returned by `successStatus` otherwise with the status returned by `failureStatus`.
    *
    * @param name the name of the created span
    * @param parentSpan the parent span
    * @param failureStatus function defining the status with which the Span will be ended in case of failure
    * @param f an unary function which parameter is the action which should be traced. The newly created span is given
    *         as a parameter in case it is needed as parent reference for further spans.
    * @return the return value of f
    */
  def traceChild[T](name: String,
                    parentSpan: Span,
                    failureStatus: Throwable => Status = unknownError)(
      f: Span => Future[T])(implicit ec: ExecutionContext): Future[T] =
    traceSpan(startChildSpan(name, parentSpan), failureStatus)(f)

  private def traceSpan[T](span: Span, failureStatus: Throwable => Status)(
      f: Span => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val result = f(span)

    result.onComplete {
      case Success(_) => endSpan(span, Status.OK)
      case Failure(e) => endSpan(span, failureStatus(e))
    }

    result
  }

  private def buildSpan(builder: SpanBuilder): Span = {
    builder
      .setSampler(Samplers.probabilitySampler(config.samplingProbability))
      .startSpan()
  }
}

object Tracing extends Tracing with LazyLogging {
  override protected val config = loadConfigOrThrow[Config]("opencensus-scala")

  if (config.stackdriver.enabled) {
    Stackdriver.init(config.stackdriver)
  }
}
