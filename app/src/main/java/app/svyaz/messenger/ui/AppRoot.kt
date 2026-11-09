package app.svyaz.messenger.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.svyaz.messenger.core.NodeRepository

@Composable
fun AppRoot(repository: NodeRepository, pendingPeer: String?, onPeerOpened: () -> Unit) {
    val settings by repository.settings.data.collectAsStateWithLifecycle()
    val loaded = settings ?: return
    val nav = rememberNavController()
    // Fixed once: a changing start destination would reset the back stack after onboarding.
    val start = remember { if (loaded.onboarded) "chats" else "onboarding" }

    LaunchedEffect(pendingPeer, loaded.onboarded) {
        if (pendingPeer != null && loaded.onboarded) {
            nav.navigate("chat/$pendingPeer") { launchSingleTop = true }
            onPeerOpened()
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(repository) {
                nav.navigate("chats") { popUpTo("onboarding") { inclusive = true } }
            }
        }
        composable("chats") {
            ChatsScreen(
                repository = repository,
                onOpenChat = { nav.navigate("chat/$it") },
                onNetwork = { nav.navigate("network") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("chat/{peer}", arguments = listOf(navArgument("peer") { type = NavType.StringType })) { entry ->
            val peer = entry.arguments?.getString("peer").orEmpty()
            ChatScreen(
                repository = repository,
                peer = peer,
                onBack = { nav.popBackStack() },
                onContact = { nav.navigate("contact/$peer") },
            )
        }
        composable("contact/{peer}", arguments = listOf(navArgument("peer") { type = NavType.StringType })) { entry ->
            ContactScreen(repository, entry.arguments?.getString("peer").orEmpty()) { nav.popBackStack() }
        }
        composable("network") { NetworkScreen(repository) { nav.popBackStack() } }
        composable("settings") {
            SettingsScreen(
                repository = repository,
                onBack = { nav.popBackStack() },
                onMyQr = { nav.navigate("my-qr") },
            )
        }
        composable("my-qr") { MyQrScreen(repository) { nav.popBackStack() } }
    }
}
