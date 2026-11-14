package sk.housenail.manager

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM data messages only: the HouseNail Worker sends event=new and bookingId.
 * Restrict notifications to new bookings; other events can be added later.
 * Notifications are posted through BookingAlerts, which checks login and settings.
 */
class HouseNailFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        BookingAlerts.registerToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data["event"] != "new") return
        val id = data["bookingId"] ?: data["id"] ?: return
        BookingAlerts.receiveFcmBooking(
            applicationContext,
            id,
            data["date"].orEmpty(),
            data["time"].orEmpty(),
            data["name"].orEmpty(),
            data["service"].orEmpty()
        )
    }
}
