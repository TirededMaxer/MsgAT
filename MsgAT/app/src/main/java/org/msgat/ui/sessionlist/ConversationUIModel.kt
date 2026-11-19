package org.msgat.ui.sessionlist

data class ConversationUIModel(val uuid:String,val alias:String,val avatarPath:String="",val unreadCount:Int=0,
    val pinOrder:Long=0,val archived:Boolean=false,val muteUntil:Long=0,val manualUnread:Boolean=false,
    val summary:String="",val draftText:String="",val lastAt:Long=0,val firstUnreadID:String="",val folderIDs:String="",val hasDraft:Boolean=false,
    val activityAt:Long=0,val folderPinOrders:Map<String,Long> = emptyMap(),
    val lastReceiptStatus: Int? = null, val summaryKind: String = "", val lastMessageId: String = "") {
    val hasUnread:Boolean get()=unreadCount>0 || manualUnread
}
