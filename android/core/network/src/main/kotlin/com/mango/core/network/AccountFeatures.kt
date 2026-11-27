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
@Serializable data class PromoResultDto(val alreadyApplied:Boolean,val points:Long,val days:Int)
@Serializable data class CloudContactDto(val id:String,val name:String,val phone:String="",val email:String="",val updatedAt:String?=null)
@Serializable data class CloudContactsBatchDto(val items:List<CloudContactDto>)
@Serializable data class ContactsSavedDto(val saved:Int)

@Serializable data class CallIceServerDto(val urls:List<String>,val username:String,val credential:String)
@Serializable data class SupportTicketDto(val id:String,val subject:String,val status:String,val updatedAt:String)
@Serializable data class SupportMessageDto(val id:String,val seq:Long,val body:String,val staff:Boolean,val createdAt:String)
@Serializable data class SupportCreateDto(val id:String,val subject:String,val body:String)
@Serializable data class SupportReplyDto(val id:String,val body:String)
@Serializable data class SupportOkDto(val ok:Boolean)
@Serializable data class CallGrantDto(val url:String,val token:String,val room:String,val iceServers:List<CallIceServerDto> = emptyList(),val relayOnly:Boolean=false)

interface AccountFeaturesApi {
    @GET("v1/stories") suspend fun stories(@Query("offset") offset:Int):Response<List<StoryDto>>
    @GET("v1/stories/{id}/image") suspend fun storyImage(@Path("id") id:String):Response<StoryImageDto>
    @POST("v1/stories") suspend fun publishStory(@Body body:StoryCreateDto):Response<Unit>
    @DELETE("v1/stories/{id}") suspend fun deleteStory(@Path("id") id:String):Response<Unit>
    @GET("v1/support") suspend fun support(@Query("offset") offset:Int):Response<List<SupportTicketDto>>
    @GET("v1/support/{id}") suspend fun supportMessages(@Path("id") id:String,@Query("before") before:Long?):Response<List<SupportMessageDto>>
    @POST("v1/support") suspend fun createSupport(@Body body:SupportCreateDto):Response<ReportResultDto>
    @POST("v1/support/{id}/reply") suspend fun replySupport(@Path("id") id:String,@Body body:SupportReplyDto):Response<SupportOkDto>
    @POST("v1/conversations/{id}/call/join") suspend fun joinCall(@Path("id") id:String):Response<CallGrantDto>
    @POST("v1/users/me/promos") suspend fun redeemPromo(@Body code:ReferralCodeDto):Response<PromoResultDto>
    @GET("v1/users/me/contacts") suspend fun cloudContacts():Response<List<CloudContactDto>>
    @DELETE("v1/users/me/contacts/{id}") suspend fun removeCloudContact(@Path("id") id:String):Response<Unit>
    @POST("v1/users/me/contacts/sync") suspend fun syncContacts(@Body batch:CloudContactsBatchDto):Response<ContactsSavedDto>
    @POST("v1/nearby") suspend fun nearbyPublish(@Body location:NearbyLocationDto):Response<NearbyVisibilityDto>
    @GET("v1/nearby") suspend fun nearby():Response<List<NearbyPersonDto>>
    @DELETE("v1/nearby") suspend fun nearbyHide():Response<NearbyVisibilityDto>
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
    suspend fun stories(offset:Int=0)=apiCall{api.stories(offset)}
    suspend fun storyImage(id:String)=apiCall{api.storyImage(id)}
    suspend fun publishStory(id:String,image:String)=apiCall{api.publishStory(StoryCreateDto(id,image))}
    suspend fun deleteStory(id:String)=apiCall{api.deleteStory(id)}
    suspend fun support(offset:Int=0)=apiCall{api.support(offset)}
    suspend fun supportMessages(id:String,before:Long?=null)=apiCall{api.supportMessages(id,before)}
    suspend fun createSupport(body:SupportCreateDto)=apiCall{api.createSupport(body)}
    suspend fun replySupport(id:String,body:SupportReplyDto)=apiCall{api.replySupport(id,body)}
    suspend fun joinCall(id:String)=apiCall{api.joinCall(id)}
    suspend fun redeemPromo(code:String)=apiCall{api.redeemPromo(ReferralCodeDto(code))}
    suspend fun cloudContacts()=apiCall{api.cloudContacts()}
    suspend fun removeCloudContact(id:String)=apiCall{api.removeCloudContact(id)}
    suspend fun syncContacts(items:List<CloudContactDto>)=apiCall{api.syncContacts(CloudContactsBatchDto(items.map{it.copy(updatedAt=null)}))}
    suspend fun nearbyPublish(latitude:Double,longitude:Double) = apiCall { api.nearbyPublish(NearbyLocationDto(latitude,longitude)) }
    suspend fun nearby() = apiCall { api.nearby() }
    suspend fun nearbyHide() = apiCall { api.nearbyHide() }
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
