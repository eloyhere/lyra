package com.eloykim.lyra.service

import android.content.Context
import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.service.address.AddressService
import com.eloykim.lyra.service.alarm.AlarmService
import com.eloykim.lyra.service.application.ApplicationService
import com.eloykim.lyra.service.calendar.CalendarService
import com.eloykim.lyra.service.chatmessage.ChatMessageService
import com.eloykim.lyra.service.chatsession.ChatSessionService
import com.eloykim.lyra.service.contacts.ContactsService
import com.eloykim.lyra.service.groupchat.GroupChatService
import com.eloykim.lyra.service.database.DatabaseService
import com.eloykim.lyra.service.database.ReadOnlyDatabase
import com.eloykim.lyra.service.device.DeviceService
import com.eloykim.lyra.service.dialog.DialogService
import com.eloykim.lyra.service.file.FileService
import com.eloykim.lyra.service.impression.ImpressionService
import com.eloykim.lyra.service.intent.IntentService
import com.eloykim.lyra.service.media.MediaService
import com.eloykim.lyra.service.mission.MissionService
import com.eloykim.lyra.service.network.NetworkService
import com.eloykim.lyra.service.photo.PhotoService
import com.eloykim.lyra.service.notification.NotificationService
import com.eloykim.lyra.service.provider.ProviderService
import com.eloykim.lyra.service.screen.ScreenService
import com.eloykim.lyra.service.screenshot.ScreenshotService
import com.eloykim.lyra.service.shortmessage.ShortMessageService
import com.eloykim.lyra.service.snackbar.SnackbarService
import com.eloykim.lyra.service.status.StatusService
import com.eloykim.lyra.service.swipe.SwipeService
import com.eloykim.lyra.service.task.TaskService
import com.eloykim.lyra.service.text.TextService
import com.eloykim.lyra.service.touch.TouchService
import com.eloykim.lyra.service.usage.UsageService
import com.eloykim.lyra.service.voice.VoiceService
import com.eloykim.lyra.service.window.WindowService

/**
 * 默认服务集合。
 *
 * 需要 app context 与已初始化的 [HistoryRepository]；通常在 AppGraph 初始化后
 * 通过 `AppGraph.commands` 惰性创建。
 */
object PhoneServices {

    fun create(
        context: Context,
        history: HistoryRepository? = null,
    ): ServiceHub {
        val app = context.applicationContext
        val repository = history ?: AppGraph.history
        val database = ReadOnlyDatabase(app)
        val provider = ProviderService()

        val hub = ServiceHub(
            listOf(
                ScreenService(),
                TaskService(repository),
                MissionService(app),
                WindowService(app),
                provider,
                StatusService(app, repository),
                UsageService(repository),
                NetworkService(app),
                DatabaseService(app, database),
                ImpressionService(repository, app, database),
                TouchService(),
                SwipeService(app),
                TextService(app),
                ScreenshotService(app),
                ApplicationService(app, repository),
                ChatMessageService(repository.chatMessages),
                ChatSessionService(repository),
                GroupChatService(repository),
                AddressService(repository),
                FileService(app),
                MediaService(app),
                PhotoService(app),
                ShortMessageService(app),
                VoiceService(app),
                NotificationService(app),
                SnackbarService(),
                DialogService(),
                DeviceService(app),
                IntentService(app),
                AlarmService(app),
                ContactsService(app),
                CalendarService(app),
            ),
            context = app,
        )
        // ProviderService 需要反向查询整个 hub；构造完成后再注入。
        provider.attach(hub)
        return hub
    }

    fun createCommands(
        context: Context,
        history: HistoryRepository? = null,
    ): CommandRegistry = CommandRegistry(create(context, history))
}
