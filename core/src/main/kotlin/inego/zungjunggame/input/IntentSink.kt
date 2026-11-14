package inego.zungjunggame.input

/** Receives [Intent]s from input; the single outbound seam for the pipeline. */
fun interface IntentSink {
    fun emit(intent: Intent)
}

/** Ordered handler; returning true consumes the intent and stops dispatch. */
fun interface IntentHandler {
    fun handle(intent: Intent): Boolean
}
