package com.chipprbots.ethereum.metrics

case class MetricsAlreadyConfiguredError(previous: Metrics, current: Metrics) extends Exception
