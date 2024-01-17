package com.google.android.fhir.sync

import timber.log.Timber
import kotlin.system.measureTimeMillis
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

@OptIn(ExperimentalTime::class)
inline fun measureSyncTime(label: String, block:() -> Unit) {
  val time = measureTime {
    block()
  }
  Timber.i("MeasureSyncTime: function $label took $time to run")
}