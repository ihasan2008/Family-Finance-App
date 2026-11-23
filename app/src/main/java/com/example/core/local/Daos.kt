package com.example.core.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    fun observeUserProfile(userId: String): Flow<LocalUserProfileEntity?>

    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    suspend fun getUserProfile(userId: String): LocalUserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserProfile(profile: LocalUserProfileEntity)

    @Query("DELETE FROM user_profiles WHERE userId = :userId")
    suspend fun deleteUserProfile(userId: String)

    @Query("DELETE FROM user_profiles")
    suspend fun deleteAll()
}

@Dao
interface FamilyDao {
    @Query("SELECT * FROM families WHERE memberUidsCsv LIKE '%' || :userId || '%' ORDER BY updatedAtMillis DESC")
    fun observeFamiliesForUser(userId: String): Flow<List<LocalFamilyEntity>>

    @Query("SELECT * FROM families WHERE familyId = :familyId LIMIT 1")
    suspend fun getFamilyById(familyId: String): LocalFamilyEntity?

    @Query("SELECT * FROM families ORDER BY updatedAtMillis DESC")
    suspend fun getAllFamilies(): List<LocalFamilyEntity>

    @Query("SELECT * FROM families WHERE ownerId = :userId OR createdByUserId = :userId ORDER BY updatedAtMillis DESC")
    suspend fun getAdminLinkedFamilies(userId: String): List<LocalFamilyEntity>

    @Query("SELECT * FROM families WHERE syncStatus = 'PENDING_SYNC' AND ownerId = :userId")
    suspend fun getPendingFamilies(userId: String): List<LocalFamilyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFamilies(families: List<LocalFamilyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFamily(family: LocalFamilyEntity)

    @Query("DELETE FROM families WHERE familyId NOT IN (:activeIds) AND syncStatus = 'SYNCED' AND memberUidsCsv LIKE '%' || :userId || '%'")
    suspend fun pruneSyncedFamiliesNotIn(userId: String, activeIds: List<String>)

    @Query("DELETE FROM families WHERE syncStatus = 'SYNCED' AND memberUidsCsv LIKE '%' || :userId || '%'")
    suspend fun clearSyncedFamiliesForUser(userId: String)

    @Query("DELETE FROM families WHERE familyId = :familyId")
    suspend fun deleteFamilyById(familyId: String)

    @Query("DELETE FROM families")
    suspend fun deleteAll()
}

@Dao
interface FamilyMemberDao {
    @Query("SELECT * FROM family_members WHERE familyId = :familyId ORDER BY role ASC, displayName ASC")
    fun observeMembersForFamily(familyId: String): Flow<List<LocalFamilyMemberEntity>>

    @Query("SELECT * FROM family_members WHERE familyId = :familyId AND userId = :userId LIMIT 1")
    suspend fun getMember(familyId: String, userId: String): LocalFamilyMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<LocalFamilyMemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: LocalFamilyMemberEntity)

    @Query("DELETE FROM family_members WHERE familyId = :familyId AND userId = :userId")
    suspend fun deleteMember(familyId: String, userId: String)

    @Query("DELETE FROM family_members WHERE familyId = :familyId AND userId NOT IN (:activeUserIds) AND syncStatus = 'SYNCED'")
    suspend fun pruneSyncedMembersNotIn(familyId: String, activeUserIds: List<String>)

    @Query("DELETE FROM family_members WHERE familyId = :familyId")
    suspend fun clearMembersForFamily(familyId: String)

    @Query("DELETE FROM family_members")
    suspend fun deleteAll()
}

@Dao
interface MyJoinRequestDao {
    @Query("SELECT * FROM my_join_requests WHERE userId = :userId ORDER BY updatedAtMillis DESC")
    fun observeJoinRequestsForUser(userId: String): Flow<List<LocalMyJoinRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJoinRequest(request: LocalMyJoinRequestEntity)

    @Query("DELETE FROM my_join_requests WHERE familyId = :familyId AND userId = :userId")
    suspend fun deleteJoinRequest(familyId: String, userId: String)

    @Query("DELETE FROM my_join_requests")
    suspend fun deleteAll()
}

@Dao
interface MemberCardDao {
    @Query("SELECT * FROM member_cards WHERE familyId = :familyId ORDER BY role ASC, displayName ASC")
    fun observeByFamily(familyId: String): Flow<List<LocalMemberCardEntity>>

    @Query("SELECT * FROM member_cards WHERE familyId = :familyId AND userId = :userId LIMIT 1")
    suspend fun getCard(familyId: String, userId: String): LocalMemberCardEntity?

    @Query("SELECT * FROM member_cards WHERE familyId = :familyId AND cardMemberId = :cardMemberId LIMIT 1")
    suspend fun findByCardMemberId(familyId: String, cardMemberId: String): LocalMemberCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: LocalMemberCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cards: List<LocalMemberCardEntity>)

    @Query("DELETE FROM member_cards WHERE familyId = :familyId AND userId = :userId")
    suspend fun deleteByFamilyAndUser(familyId: String, userId: String)

    @Query("DELETE FROM member_cards WHERE familyId = :familyId")
    suspend fun deleteByFamilyId(familyId: String)

    @Query("DELETE FROM member_cards")
    suspend fun deleteAll()
}

