package com.mbmlife.companion.data

import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class FamilyResolver(
    private val firestore: FirebaseFirestore,
    private val logger: DiagnosticLogger
) {
    /**
     * Resolves a single family membership without trusting WebView state.
     * If the account belongs to more than one family, selection remains a UI
     * decision and this method deliberately returns null.
     */
    suspend fun resolveSingleFamily(uid: String): String? {
        return try {
            val roles = listOf("owner", "admin", "adult", "child", "guest")
            val result = firestore.collection("families")
                .whereIn(FieldPath.of("members", uid, "role"), roles)
                .limit(2)
                .get()
                .await()
            when (result.size()) {
                1 -> result.documents.first().id.also {
                    logger.info(
                        "Family",
                        "Single family resolved from Firestore",
                        JSONObject().put("uid", uid).put("familyId", it).toString()
                    )
                }
                0 -> null.also {
                    logger.warn("Family", "No family membership found for authenticated user")
                }
                else -> null.also {
                    logger.warn(
                        "Family",
                        "Multiple families found; WebView active-family selection required"
                    )
                }
            }
        } catch (error: Exception) {
            logger.error(
                "Family",
                "Family membership query failed",
                JSONObject()
                    .put("errorClass", error::class.java.name)
                    .put("message", error.message)
                    .toString()
            )
            null
        }
    }
}
