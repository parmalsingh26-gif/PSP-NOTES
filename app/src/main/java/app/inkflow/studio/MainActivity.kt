package app.inkflow.studio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.inkflow.studio.core.design.InkFlowTheme
import app.inkflow.studio.domain.model.DocumentType
import app.inkflow.studio.feature.editor.NotebookEditorScreen
import app.inkflow.studio.feature.library.LibraryScreen
import app.inkflow.studio.feature.study.ui.StudyScreen
import app.inkflow.studio.feature.whiteboard.WhiteboardScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as InkFlowApplication
        val documentRepository = app.documentRepository
        val studyRepository = app.studyRepository

        setContent {
            InkFlowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(
                        documentRepository = documentRepository,
                        studyRepository = studyRepository
                    )
                }
            }
        }

        // Handle incoming PDF VIEW intent
        handleIntent(intent, documentRepository)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val app = application as InkFlowApplication
        handleIntent(intent, app.documentRepository)
    }

    private fun handleIntent(intent: Intent?, repository: app.inkflow.studio.data.repository.DocumentRepository) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null && (intent.type == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true))) {
                lifecycleScope.launch {
                    val doc = repository.createPdfDocument("Imported Document", uri, 1)
                    // Handled gracefully in library list
                }
            }
        }
    }
}

@Composable
fun AppNavigation(
    documentRepository: app.inkflow.studio.data.repository.DocumentRepository,
    studyRepository: app.inkflow.studio.data.repository.StudyRepository
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                repository = documentRepository,
                onOpenDocument = { doc ->
                    when (doc.type) {
                        DocumentType.WHITEBOARD -> navController.navigate("whiteboard/${doc.id}")
                        else -> navController.navigate("editor/${doc.id}")
                    }
                },
                onOpenStudy = {
                    navController.navigate("study")
                }
            )
        }

        composable(
            route = "editor/{documentId}",
            arguments = listOf(navArgument("documentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId") ?: ""
            NotebookEditorScreen(
                documentId = documentId,
                repository = documentRepository,
                studyRepository = studyRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "whiteboard/{documentId}",
            arguments = listOf(navArgument("documentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getString("documentId") ?: ""
            WhiteboardScreen(
                documentId = documentId,
                repository = documentRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("study") {
            StudyScreen(
                studyRepository = studyRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

