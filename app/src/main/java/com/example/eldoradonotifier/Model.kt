package com.example.eldoradonotifier

import org.json.JSONObject

/** One of the seller's own offers (used for the top-up pause/resume screen). */
data class Offer(
    val id: String,
    val flexible: Boolean,
    val title: String,
    val game: String,
    val state: String,
    val price: Double?,
)

/** One Eldorado notification, flattened from NotificationPrivateGetDTO. */
data class Notif(
    val id: String,
    val event: String,
    val dateIso: String,
    val offerTitle: String?,
    val buyer: String?,
    val game: String?,
    val amount: Double?,
    val subject: String?,
    val text: String?,
    val redirectUrl: String?,
    val unread: Boolean = false,
) {
    val title: String
        get() = EVENT_TITLES[event]
            ?: subject?.takeIf { it.isNotBlank() }
            ?: event

    val body: String
        get() {
            val parts = mutableListOf<String>()
            offerTitle?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            game?.takeIf { it.isNotBlank() }?.let { if (it != offerTitle) parts.add(it) }
            buyer?.takeIf { it.isNotBlank() }?.let { parts.add("المشتري: $it") }
            if (amount != null && !amount.isNaN() && amount > 0) parts.add("$" + String.format(java.util.Locale.US, "%.2f", amount))
            if (event == "Custom" || parts.isEmpty()) {
                text?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
            return parts.joinToString(" • ").ifBlank { event }
        }

    companion object {
        private val EVENT_TITLES = mapOf(
            "OrderCreated" to "🛒 أوردر جديد",
            "OrderDelivered" to "📦 تم تسليم أوردر",
            "OrderDisputed" to "⚠️ نزاع على أوردر",
            "OrderReceived" to "✅ المشتري استلم الأوردر",
            "OrderReceivedByBuyerAfterDispute" to "✅ المشتري استلم بعد النزاع",
            "OrderReceivedByAdminAfterDispute" to "✅ الإدارة أنهت النزاع (استلام)",
            "OrderCancelledBySeller" to "❌ أوردر اتلغى منك",
            "OrderCancelledByAdmin" to "❌ الإدارة لغت أوردر",
            "OrderCancelledByAdminAfterDispute" to "❌ أوردر اتلغى بعد النزاع",
            "OrderCancelledBySellerAfterDispute" to "❌ أوردر اتلغى بعد النزاع",
            "ReviewNeedsResubmit" to "⭐ التقييم محتاج إعادة إرسال",
            "ReviewCompleted" to "⭐ تقييم جديد",
            "ReviewClosed" to "⭐ تقييم اتقفل",
            "SellerReviewClosed" to "⭐ تقييم اتقفل",
            "UserSuspended" to "🚫 حسابك اتوقف",
            "UserUnsuspended" to "✅ حسابك رجع شغال",
            "WarrantyClaimIssued" to "🛡️ طلب ضمان جديد",
            "WarrantyClaimDenied" to "🛡️ طلب الضمان اترفض",
            "WarrantyClaimApprovedSellerCovered" to "🛡️ طلب ضمان اتقبل (عليك)",
            "WarrantyClaimApprovedEldoradoCovered" to "🛡️ طلب ضمان اتقبل (على الدورادو)",
            "ConversationCreated" to "💬 محادثة جديدة",
            "ConversationCreatedByAdmin" to "💬 الإدارة فتحت محادثة",
            "BuyerContactedSeller" to "💬 مشتري كلمك",
            "TalkJsNudge" to "💬 عندك رسالة لسه متقراتش",
            "PaymentCompleted" to "💰 دفع مكتمل",
            "SellerExtendedDeliveryTime" to "⏳ اتمدد وقت التسليم",
            "DeliveryDetailsReminderMin15" to "⏰ تفاصيل التسليم: فاضل 15 دقيقة",
            "DeliveryDetailsReminderHour1" to "⏰ تفاصيل التسليم: فاضل ساعة",
            "DeliveryDetailsReminderHour3" to "⏰ تفاصيل التسليم: فاضل 3 ساعات",
            "SellerRequestedDeliveryDetailsCorrection" to "✏️ طلب تصحيح تفاصيل التسليم",
            "DisputeProofResubmitRequested" to "⚠️ مطلوب إعادة إرسال إثبات النزاع",
            "ItemAutoDeliveryCancelled" to "❌ التسليم التلقائي اتلغى",
            "ItemAutoDeliveryOrderNotClaimed" to "⚠️ أوردر تسليم تلقائي لسه متستلمش",
            "BoostingRequestCreated" to "🚀 طلب بوستنج جديد",
            "BoostingOfferCreated" to "🚀 عرض بوستنج جديد",
            "SellerVerificationCaseApproved" to "✅ التحقق من البائع اتقبل",
            "SellerVerificationCaseRejected" to "❌ التحقق من البائع اترفض",
            "SellerVerificationReviewNeedsResubmit" to "⚠️ التحقق محتاج إعادة إرسال",
        )

        fun fromJson(item: JSONObject): Notif? {
            val n = item.optJSONObject("notification") ?: return null
            val id = n.optString("id", "")
            if (id.isEmpty()) return null
            val d = n.optJSONObject("details")
            val c = item.optJSONObject("customNotification")
            val price = d?.optJSONObject("price")
            val amount = if (price != null && price.has("amount")) price.optDouble("amount") else null
            return Notif(
                id = id,
                event = n.optString("event", "Unknown"),
                dateIso = n.optString("notificationDate", ""),
                offerTitle = d?.optString("title")?.takeIf { it != "null" },
                buyer = d?.optString("buyerUsername")?.takeIf { it != "null" },
                game = d?.optString("gameCategoryTitle")?.takeIf { it != "null" },
                amount = amount,
                subject = c?.optString("subject")?.takeIf { it != "null" },
                text = c?.optString("text")?.takeIf { it != "null" },
                redirectUrl = c?.optString("redirectUrl")?.takeIf { it != "null" },
                unread = n.optString("notificationReadStatus") == "IsUnread",
            )
        }
    }
}
