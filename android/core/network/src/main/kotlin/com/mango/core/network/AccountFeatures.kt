package com.mango.core.network

import com.mango.core.common.Outcome
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.*
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class AccountBalanceDto(val points:Long=0,val subscriptionUntil:String?=null)
@Serializable data class AccountHistoryDto(val id:String,val createdAt:String,val oldPoints:Long,val newPoints:Long,
    val oldSubscription:String?=null,val newSubscription:String?=null,val reason:String)
@Serializable data class ReportRequestDto(val targetId:String,val reason:String)
@Serializable data class ReportResultDto(val id:String)
@Serializable data class TranscriptDto(val text:String,val language:String,val model:String)
@Serializable data class WelcomeTaskDto(val kind:String,val status:String)
@Serializable data class WelcomeStatusDto(val status:String)
@Serializable data class AvatarRequestDto(val image:String)
@Serializable data class AvatarResultDto(val avatarUrl:String)
@Serializable data class ProfileUpdateDto(val displayName:String,val username:String,val bio:String)
@Serializable data class ReferralDto(val code:String,val invited:Int,val applied:Boolean)
@Serializable data class ReferralCodeDto(val code:String)
@Serializable data class ReferralAppliedDto(val applied:Boolean)
@Serializable data class AdminAccessDto(val role:String)

interface AccountFeaturesApi {
    @GET("v1/admin/me") suspend fun adminAccess():Response<AdminAccessDto>
    @PATCH("v1/users/me/referral") suspend fun editReferral(@Body code:ReferralCodeDto):Response<ReferralCodeDto>
    @GET("v1/users/me/referral") suspend fun referral():Response<ReferralDto>
    @POST("v1/users/me/referral") suspend fun referral(@Body code:ReferralCodeDto):Response<ReferralAppliedDto>
    @GET("v1/users/me") suspend fun profile():Response<com.mango.core.network.dto.CurrentUserDto>
    @PATCH("v1/users/me") suspend fun profile(@Body request:ProfileUpdateDto):Response<com.mango.core.network.dto.CurrentUserDto>
    @POST("v1/users/me/avatar") suspend fun avatar(@Body request:AvatarRequestDto):Response<AvatarResultDto>
    @GET("v1/users/me/welcome") suspend fun welcome():Response<List<WelcomeTaskDto>>
    @POST("v1/users/me/welcome/{kind}") suspend fun welcomeStatus(@Path("kind") kind:String,@Body request:WelcomeStatusDto):Response<WelcomeTaskDto>
    @GET("v1/users/me/account") suspend fun account():Response<AccountBalanceDto>
    @GET("v1/users/me/account/history") suspend fun history(@Query("offset") offset:Int):Response<List<AccountHistoryDto>>
    @POST("v1/reports") suspend fun report(@Body request:ReportRequestDto):Response<ReportResultDto>
    @POST("v1/attachments/{id}/transcript") suspend fun transcript(@Path("id") id:String):Response<TranscriptDto>
}

@Singleton class AccountFeaturesRemote @Inject constructor(private val api:AccountFeaturesApi) {
    suspend fun adminAccess() = apiCall {api.adminAccess()}
    suspend fun referral() = apiCall {api.referral()}
    suspend fun editReferral(code:String) = apiCall {api.editReferral(ReferralCodeDto(code))}
    suspend fun applyReferral(code:String) = apiCall {api.referral(ReferralCodeDto(code))}
    suspend fun profile() = apiCall {api.profile()}
    suspend fun profile(name:String,username:String,bio:String) = apiCall {api.profile(ProfileUpdateDto(name,username,bio))}
    suspend fun avatar(image:String):Outcome<AvatarResultDto> = apiCall {api.avatar(AvatarRequestDto(image))}
    suspend fun welcome():Outcome<List<WelcomeTaskDto>> = apiCall {api.welcome()}
    suspend fun welcomeStatus(kind:String,status:String):Outcome<WelcomeTaskDto> = apiCall {api.welcomeStatus(kind,WelcomeStatusDto(status))}
    suspend fun account():Outcome<AccountBalanceDto> = apiCall {api.account()}
    suspend fun history(offset:Int=0):Outcome<List<AccountHistoryDto>> = apiCall {api.history(offset)}
    suspend fun report(target:String,reason:String):Outcome<ReportResultDto> = apiCall {api.report(ReportRequestDto(target,reason))}
    suspend fun transcript(id:String):Outcome<TranscriptDto> = apiCall {api.transcript(id)}
}
