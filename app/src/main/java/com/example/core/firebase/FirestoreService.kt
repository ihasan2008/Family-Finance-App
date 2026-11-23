package com.example.core.firebase

import com.example.core.remote.OperationType
import com.example.core.remote.classifyFirebaseError
import com.example.core.remote.handleFirestoreError
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Reusable Cloud Firestore service module providing safe typed observation,
 * server-timestamp-aware deserialization, metadata tracking, and Result-wrapped writes.
 */
class FirestoreService(
    val db: FirebaseFirestore,
    private val connectionMonitor: ConnectionMonitor? = null
) {
    fun <T : Any> observeDocument(
        docRef: DocumentReference,
        clazz: Class<T>,
        pathForLogs: String,
        activeUidProvider: () -> String?
    ): Flow<T?> = docRef.snapshots(MetadataChanges.INCLUDE)
        .map { snapshot ->
            connectionMonitor?.reportSnapshotMetadata(
                isFromCache = snapshot.metadata.isFromCache,
                hasPendingWrites = snapshot.metadata.hasPendingWrites()
            )
            snapshot.toObject(clazz, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val toLog = firestoreError ?: (error as? Exception)
            if (toLog != null && !activeUidProvider().isNullOrBlank()) {
                handleFirestoreError(toLog, OperationType.GET, pathForLogs)
            }
            throw error
        }

    fun <T : Any> observeQuery(
        query: Query,
        clazz: Class<T>,
        pathForLogs: String,
        activeUidProvider: () -> String?
    ): Flow<List<T>> = query.snapshots(MetadataChanges.INCLUDE)
        .map { snapshot ->
            connectionMonitor?.reportSnapshotMetadata(
                isFromCache = snapshot.metadata.isFromCache,
                hasPendingWrites = snapshot.metadata.hasPendingWrites()
            )
            snapshot.toObjects(clazz, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val toLog = firestoreError ?: (error as? Exception)
            if (toLog != null && !activeUidProvider().isNullOrBlank()) {
                handleFirestoreError(toLog, OperationType.LIST, pathForLogs)
            }
            throw error
        }

    suspend fun setDocument(
        docRef: DocumentReference,
        payload: Map<String, Any>,
        pathForLogs: String = docRef.path
    ): Result<Unit> = executeWrite(OperationType.CREATE, pathForLogs) {
        docRef.set(payload).await()
    }

    suspend fun updateDocument(
        docRef: DocumentReference,
        updates: Map<String, Any>,
        pathForLogs: String = docRef.path
    ): Result<Unit> = executeWrite(OperationType.UPDATE, pathForLogs) {
        docRef.update(updates).await()
    }

    suspend fun deleteDocument(
        docRef: DocumentReference,
        pathForLogs: String = docRef.path
    ): Result<Unit> = executeWrite(OperationType.DELETE, pathForLogs) {
        docRef.delete().await()
    }

    suspend fun commitBatch(
        pathForLogs: String,
        block: (WriteBatch) -> Unit
    ): Result<Unit> = executeWrite(OperationType.WRITE, pathForLogs) {
        val batch = db.batch()
        block(batch)
        batch.commit().await()
    }

    suspend fun checkConnectivity(): Result<Unit> = try {
        db.waitForPendingWrites().await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val failure = classifyFirebaseError(e)
        Result.failure(IllegalStateException(failure.userMessage, e))
    }

    private suspend inline fun executeWrite(
        operationType: OperationType,
        path: String,
        crossinline block: suspend () -> Unit
    ): Result<Unit> {
        return try {
            block()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, operationType, path)
            val failure = classifyFirebaseError(e)
            Result.failure(IllegalStateException(failure.userMessage, e))
        }
    }
}
