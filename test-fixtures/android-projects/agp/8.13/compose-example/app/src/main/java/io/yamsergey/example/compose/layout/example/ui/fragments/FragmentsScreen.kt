package io.yamsergey.example.compose.layout.example.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import io.yamsergey.example.compose.layout.example.ui.theme.ComposeLayoutExampleTheme

/**
 * Screen for testing fragment overlay scenarios.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FragmentsScreen(
    onNavigateBack: () -> Unit,
    onShowFragmentDemo: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fragments Demo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Nested Fragment Navigation Test",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This demo creates deeply nested fragments with multiple " +
                                "ComposeViews. When navigating, old screens stay in the " +
                                "back stack but become hidden. Tests if we correctly " +
                                "capture only the visible ComposeView.",
                        fontSize = 14.sp
                    )
                }
            }

            Button(
                onClick = onShowFragmentDemo,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Nested Fragment Demo")
            }

            Text(
                text = "Hierarchy:",
                fontWeight = FontWeight.Bold
            )
            Text("Activity → Level1Fragment → Level2Fragment → ScreenFragment")
            Text("")
            Text(
                text = "Test steps:",
                fontWeight = FontWeight.Bold
            )
            Text("1. Open the demo - you'll see Screen A (blue)")
            Text("2. Tap 'Navigate to Screen B' - Screen B (green) appears")
            Text("3. Screen A is now HIDDEN but still in back stack")
            Text("4. Capture compose tree - should show only Screen B")
            Text("5. Tap 'Navigate to Screen C' - adds Screen C (purple)")
            Text("6. Now both A and B are hidden, only C visible")
        }
    }
}

/**
 * Activity that demonstrates deeply nested fragment navigation.
 * Simulates real-world app structure with multiple navigation levels.
 */
class FragmentDemoActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create root container
        val rootContainer = FragmentContainerView(this).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        if (savedInstanceState == null) {
            // Start with Level1Fragment (simulates app-level navigation host)
            supportFragmentManager.commit {
                add(rootContainer.id, Level1Fragment(), "level1")
            }
        }
    }
}

/**
 * Level 1 - App navigation (e.g., bottom nav destinations)
 */
class Level1Fragment : Fragment() {
    private var containerId: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val fragmentContainer = FragmentContainerView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        containerId = fragmentContainer.id
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            // Add Level2Fragment (simulates feature-level navigation)
            childFragmentManager.commit {
                add(containerId, Level2Fragment(), "level2")
            }
        }
    }
}

/**
 * Level 2 - Feature navigation (e.g., tabs within a feature)
 */
class Level2Fragment : Fragment() {
    private var containerId: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val fragmentContainer = FragmentContainerView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        containerId = fragmentContainer.id
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState == null) {
            // Start with Screen A
            childFragmentManager.commit {
                add(containerId, ScreenAFragment(), "screenA")
            }
        }
    }

    fun navigateToScreen(fragment: Fragment, tag: String) {
        childFragmentManager.commit {
            // IMPORTANT: Using add() instead of replace() - old fragment stays but becomes hidden
            // This simulates the problem scenario where old ComposeViews are still in the tree
            hide(childFragmentManager.fragments.lastOrNull() ?: return@commit)
            add(containerId, fragment, tag)
            addToBackStack(tag)
        }
    }
}

/**
 * Screen A - First compose screen (blue)
 */
class ScreenAFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposeLayoutExampleTheme {
                    ScreenContent(
                        screenName = "Screen A",
                        backgroundColor = Color(0xFF1565C0), // Blue
                        description = "This is the FIRST screen. When you navigate forward, " +
                                "this screen will be HIDDEN but still exists in the fragment back stack. " +
                                "Its ComposeView should NOT appear in the compose tree capture.",
                        items = listOf("Screen A Item 1", "Screen A Item 2", "Screen A Item 3"),
                        onNavigateNext = {
                            (parentFragment as? Level2Fragment)?.navigateToScreen(
                                ScreenBFragment(), "screenB"
                            )
                        },
                        nextScreenName = "Screen B"
                    )
                }
            }
        }
    }
}

/**
 * Screen B - Second compose screen (green)
 */
class ScreenBFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposeLayoutExampleTheme {
                    ScreenContent(
                        screenName = "Screen B",
                        backgroundColor = Color(0xFF2E7D32), // Green
                        description = "This is the SECOND screen. Screen A is now hidden behind this. " +
                                "Only THIS screen's compose tree should be captured. " +
                                "Navigate to Screen C to add another layer.",
                        items = listOf("Screen B Item 1", "Screen B Item 2", "Screen B Item 3", "Screen B Item 4"),
                        onNavigateNext = {
                            (parentFragment as? Level2Fragment)?.navigateToScreen(
                                ScreenCFragment(), "screenC"
                            )
                        },
                        nextScreenName = "Screen C"
                    )
                }
            }
        }
    }
}

/**
 * Screen C - Third compose screen (purple)
 */
class ScreenCFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ComposeLayoutExampleTheme {
                    ScreenContent(
                        screenName = "Screen C",
                        backgroundColor = Color(0xFF6A1B9A), // Purple
                        description = "This is the THIRD screen. Both Screen A and Screen B are now " +
                                "hidden behind this. Only THIS screen should appear in the compose tree. " +
                                "Use the back button to go back through the stack.",
                        items = listOf("Screen C Item 1", "Screen C Item 2"),
                        onNavigateNext = null,
                        nextScreenName = null
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(
    screenName: String,
    backgroundColor: Color,
    description: String,
    items: List<String>,
    onNavigateNext: (() -> Unit)?,
    nextScreenName: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        Text(
            text = screenName,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.9f)
            )
        ) {
            Text(
                text = description,
                modifier = Modifier.padding(16.dp),
                color = backgroundColor
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (onNavigateNext != null && nextScreenName != null) {
            Button(
                onClick = onNavigateNext,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = backgroundColor
                )
            ) {
                Text("Navigate to $nextScreenName")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = "Content unique to $screenName:",
            color = Color.White,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items.size) { index ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.9f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = items[index],
                            color = backgroundColor,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "[$screenName]",
                            color = backgroundColor.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status indicator
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Debug Info",
                    fontWeight = FontWeight.Bold,
                    color = backgroundColor
                )
                Text(
                    text = "This is $screenName's ComposeView",
                    color = backgroundColor
                )
                Text(
                    text = "If you see this in the tree, this screen is captured",
                    color = backgroundColor.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
