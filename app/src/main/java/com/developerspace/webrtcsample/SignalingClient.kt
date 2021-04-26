package com.developerspace.webrtcsample

import android.util.Log
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class SignalingClient(
    private val meetingID : String,
    private val listener: SignalingClientListener
) : CoroutineScope {

    companion object {
        private const val HOST_ADDRESS = "192.168.0.12"
    }

    var jsonObject : JSONObject?= null

    private val job = Job()

    val TAG = "SignallingClient"

    val db = Firebase.firestore

    private val gson = Gson()

    var SDPtype : String? = null
    override val coroutineContext = Dispatchers.IO + job

//    private val client = HttpClient(CIO) {
//        install(WebSockets)
//        install(JsonFeature) {
//            serializer = GsonSerializer()
//        }
//    }

    private val sendChannel = ConflatedBroadcastChannel<String>()

    init {
        connect()
    }

    private fun connect() = launch {
        db.enableNetwork().addOnSuccessListener {
            listener.onConnectionEstablished()
        }
        val sendData = sendChannel.offer("")
        sendData.let {
            Log.v(this@SignalingClient.javaClass.simpleName, "Sending: $it")
//            val data = hashMapOf(
//                    "data" to it
//            )
//            db.collection("calls")
//                    .add(data)
//                    .addOnSuccessListener { documentReference ->
//                        Log.e(TAG, "DocumentSnapshot added with ID: ${documentReference.id}")
//                    }
//                    .addOnFailureListener { e ->
//                        Log.e(TAG, "Error adding document", e)
//                    }
        }
        try {
            db.collection("calls")
                .document(meetingID)
                .addSnapshotListener { snapshot, e ->

                    if (e != null) {
                        Log.w(TAG, "listen:error", e)
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val data = snapshot.data
                        if (data?.containsKey("type")!! &&
                            data.getValue("type").toString() == "OFFER") {
                                listener.onOfferReceived(SessionDescription(
                                    SessionDescription.Type.OFFER,data["sdp"].toString()))
                            SDPtype = "Offer"
                        } else if (data?.containsKey("type") &&
                            data.getValue("type").toString() == "ANSWER") {
                                listener.onAnswerReceived(SessionDescription(
                                    SessionDescription.Type.ANSWER,data["sdp"].toString()))
                            SDPtype = "Answer"
                        } else if (!Constants.isIntiatedNow && data.containsKey("type") &&
                            data.getValue("type").toString() == "END_CALL") {
                            listener.onCallEnded()
                            SDPtype = "End Call"

                        }
                        Log.d(TAG, "Current data: ${snapshot.data}")
                    } else {
                        Log.d(TAG, "Current data: null")
                    }
                }
            db.collection("calls").document(meetingID)
                    .collection("candidates").addSnapshotListener{ querysnapshot,e->
                        if (e != null) {
                            Log.w(TAG, "listen:error", e)
                            return@addSnapshotListener
                        }

                        if (querysnapshot != null && !querysnapshot.isEmpty) {
                            for (dataSnapShot in querysnapshot) {

                                val data = dataSnapShot.data
                                if (SDPtype == "Offer" && data.containsKey("type") && data.get("type")=="offerCandidate") {
                                    listener.onIceCandidateReceived(
                                            IceCandidate(data["sdpMid"].toString(),
                                                    Math.toIntExact(data["sdpMLineIndex"] as Long),
                                                    data["sdpCandidate"].toString()))
                                } else if (SDPtype == "Answer" && data.containsKey("type") && data.get("type")=="answerCandidate") {
                                    listener.onIceCandidateReceived(
                                            IceCandidate(data["sdpMid"].toString(),
                                                    Math.toIntExact(data["sdpMLineIndex"] as Long),
                                                    data["sdpCandidate"].toString()))
                                }
                                Log.e(TAG, "candidateQuery: $dataSnapShot" )
                            }
                        }
                    }
//            db.collection("calls").document(meetingID)
//                    .get()
//                    .addOnSuccessListener { result ->
//                        val data = result.data
//                        if (data?.containsKey("type")!! && data.getValue("type").toString() == "OFFER") {
//                            Log.e(TAG, "connect: OFFER - $data")
//                            listener.onOfferReceived(SessionDescription(SessionDescription.Type.OFFER,data["sdp"].toString()))
//                        } else if (data?.containsKey("type") && data.getValue("type").toString() == "ANSWER") {
//                            Log.e(TAG, "connect: ANSWER - $data")
//                            listener.onAnswerReceived(SessionDescription(SessionDescription.Type.ANSWER,data["sdp"].toString()))
//                        }
//                    }
//                    .addOnFailureListener {
//                        Log.e(TAG, "connect: $it")
//                    }

        } catch (exception: Exception) {
            Log.e(TAG, "connectException: $exception")

        }
    }

    fun sendIceCandidate(candidate: IceCandidate?,isJoin : Boolean) = runBlocking {
        val type = when {
            isJoin -> "answerCandidate"
            else -> "offerCandidate"
        }
        val candidateConstant = hashMapOf(
                "serverUrl" to candidate?.serverUrl,
                "sdpMid" to candidate?.sdpMid,
                "sdpMLineIndex" to candidate?.sdpMLineIndex,
                "sdpCandidate" to candidate?.sdp,
                "type" to type
        )
        db.collection("calls")
            .document("$meetingID").collection("candidates").document(type)
            .set(candidateConstant as Map<String, Any>)
            .addOnSuccessListener {
                Log.e(TAG, "sendIceCandidate: Success" )
            }
            .addOnFailureListener {
                Log.e(TAG, "sendIceCandidate: Error $it" )
            }
    }

    fun destroy() {
//        client.close()
        job.complete()
    }
}
