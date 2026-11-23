package com.example.core.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ApprovalSettingsDao {
    @Query("SELECT * FROM approval_settings WHERE familyId = :familyId LIMIT 1")
    fun observeByFamily(familyId: String): Flow<LocalApprovalSettingsEntity?>

    @Query("SELECT * FROM approval_settings WHERE familyId = :familyId LIMIT 1")
    suspend fun getByFamily(familyId: String): LocalApprovalSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalApprovalSettingsEntity)

    @Query("DELETE FROM approval_settings WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM approval_settings")
    suspend fun deleteAll()
}

@Dao
interface ProposalDao {
    @Query("SELECT * FROM proposals WHERE familyId = :familyId ORDER BY updatedAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalProposalEntity>>

    @Query("SELECT * FROM proposals WHERE proposalId = :proposalId LIMIT 1")
    suspend fun getById(proposalId: String): LocalProposalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalProposalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalProposalEntity>)

    @Query("DELETE FROM proposals WHERE proposalId = :proposalId AND familyId = :familyId")
    suspend fun deleteById(proposalId: String, familyId: String)

    @Query("DELETE FROM proposals WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM proposals")
    suspend fun deleteAll()
}

@Dao
interface ProposalVoteDao {
    @Query("SELECT * FROM proposal_votes WHERE proposalId = :proposalId ORDER BY votedAtMillis ASC")
    fun observeByProposal(proposalId: String): Flow<List<LocalProposalVoteEntity>>

    @Query("SELECT * FROM proposal_votes WHERE familyId = :familyId ORDER BY updatedAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalProposalVoteEntity>>

    @Query("SELECT * FROM proposal_votes WHERE proposalId = :proposalId AND voterUid = :voterUid LIMIT 1")
    suspend fun getVote(proposalId: String, voterUid: String): LocalProposalVoteEntity?

    @Query("SELECT * FROM proposal_votes WHERE proposalId = :proposalId")
    suspend fun getVotesForProposal(proposalId: String): List<LocalProposalVoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalProposalVoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalProposalVoteEntity>)

    @Query("DELETE FROM proposal_votes WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM proposal_votes")
    suspend fun deleteAll()
}

@Dao
interface ProposalVoteHistoryDao {
    @Query("SELECT * FROM proposal_vote_history WHERE proposalId = :proposalId ORDER BY createdAtMillis DESC")
    fun observeByProposal(proposalId: String): Flow<List<LocalProposalVoteHistoryEntity>>

    @Query("SELECT * FROM proposal_vote_history WHERE familyId = :familyId ORDER BY createdAtMillis DESC")
    fun observeByFamily(familyId: String): Flow<List<LocalProposalVoteHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalProposalVoteHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LocalProposalVoteHistoryEntity>)

    @Query("DELETE FROM proposal_vote_history WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM proposal_vote_history")
    suspend fun deleteAll()
}
