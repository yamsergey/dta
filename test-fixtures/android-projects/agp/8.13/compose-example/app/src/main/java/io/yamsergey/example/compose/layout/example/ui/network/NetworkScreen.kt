package io.yamsergey.example.compose.layout.example.ui.network

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data class representing a post from JSONPlaceholder API.
 */
data class Post(
    val userId: Int,
    val id: Int,
    val title: String,
    val body: String
)

/**
 * Data class representing a user from JSONPlaceholder API.
 */
data class User(
    val id: Int,
    val name: String,
    val username: String,
    val email: String,
    val phone: String,
    val website: String
)

/**
 * Data class representing a todo from JSONPlaceholder API.
 */
data class Todo(
    val userId: Int,
    val id: Int,
    val title: String,
    val completed: Boolean
)

/**
 * Data class representing an image from Picsum API.
 */
data class PicsumImage(
    val id: String,
    val author: String,
    val width: Int,
    val height: Int,
    val url: String,
    val download_url: String
)

/**
 * Data class for loaded image with bitmap.
 */
data class LoadedImage(
    val info: PicsumImage,
    val bitmap: android.graphics.Bitmap?
)

sealed class NetworkState<out T> {
    data object Loading : NetworkState<Nothing>()
    data class Success<T>(val data: T) : NetworkState<T>()
    data class Error(val message: String) : NetworkState<Nothing>()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var postsState by remember { mutableStateOf<NetworkState<List<Post>>>(NetworkState.Loading) }
    var usersState by remember { mutableStateOf<NetworkState<List<User>>>(NetworkState.Loading) }
    var todosState by remember { mutableStateOf<NetworkState<List<Todo>>>(NetworkState.Loading) }
    var urlConnState by remember { mutableStateOf<NetworkState<List<Post>>>(NetworkState.Loading) }
    var asyncState by remember { mutableStateOf<NetworkState<List<Post>>>(NetworkState.Loading) }
    var imagesState by remember { mutableStateOf<NetworkState<List<LoadedImage>>>(NetworkState.Loading) }

    val client = remember { OkHttpClient() }
    val gson = remember { Gson() }

    // Fetch data based on selected tab
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            0 -> if (postsState is NetworkState.Loading) {
                scope.launch {
                    postsState = fetchPosts(client, gson)
                }
            }
            1 -> if (usersState is NetworkState.Loading) {
                scope.launch {
                    usersState = fetchUsers(client, gson)
                }
            }
            2 -> if (todosState is NetworkState.Loading) {
                scope.launch {
                    todosState = fetchTodos(client, gson)
                }
            }
            3 -> if (urlConnState is NetworkState.Loading) {
                scope.launch {
                    urlConnState = fetchPostsWithUrlConnection(gson)
                }
            }
            4 -> if (asyncState is NetworkState.Loading) {
                // Use enqueue (async) instead of execute (sync)
                fetchPostsAsync(client, gson) { result ->
                    asyncState = result
                }
            }
            5 -> if (imagesState is NetworkState.Loading) {
                scope.launch {
                    imagesState = fetchImages(client, gson)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Demo") },
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
        ) {
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Posts") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Users") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Todos") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("URLConn") }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text("Async") }
                )
                Tab(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    text = { Text("Images") }
                )
            }

            // Content based on selected tab
            when (selectedTab) {
                0 -> PostsContent(
                    state = postsState,
                    onRefresh = {
                        postsState = NetworkState.Loading
                        scope.launch { postsState = fetchPosts(client, gson) }
                    },
                    client = client,
                    gson = gson
                )
                1 -> UsersContent(
                    state = usersState,
                    onRefresh = {
                        usersState = NetworkState.Loading
                        scope.launch { usersState = fetchUsers(client, gson) }
                    }
                )
                2 -> TodosContent(
                    state = todosState,
                    onRefresh = {
                        todosState = NetworkState.Loading
                        scope.launch { todosState = fetchTodos(client, gson) }
                    }
                )
                3 -> UrlConnectionContent(
                    state = urlConnState,
                    onRefresh = {
                        urlConnState = NetworkState.Loading
                        scope.launch { urlConnState = fetchPostsWithUrlConnection(gson) }
                    }
                )
                4 -> AsyncContent(
                    state = asyncState,
                    onRefresh = {
                        asyncState = NetworkState.Loading
                        fetchPostsAsync(client, gson) { result ->
                            asyncState = result
                        }
                    },
                    client = client,
                    gson = gson
                )
                5 -> ImagesContent(
                    state = imagesState,
                    onRefresh = {
                        imagesState = NetworkState.Loading
                        scope.launch { imagesState = fetchImages(client, gson) }
                    }
                )
            }
        }
    }
}

@Composable
private fun PostsContent(
    state: NetworkState<List<Post>>,
    onRefresh: () -> Unit,
    client: OkHttpClient,
    gson: Gson
) {
    when (state) {
        is NetworkState.Loading -> LoadingContent()
        is NetworkState.Error -> ErrorContent(state.message, onRefresh)
        is NetworkState.Success -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    RefreshButton(onRefresh)
                }
                item {
                    CreatePostButton(client, gson)
                }
                items(state.data) { post ->
                    PostCard(post)
                }
            }
        }
    }
}

@Composable
private fun UsersContent(
    state: NetworkState<List<User>>,
    onRefresh: () -> Unit
) {
    when (state) {
        is NetworkState.Loading -> LoadingContent()
        is NetworkState.Error -> ErrorContent(state.message, onRefresh)
        is NetworkState.Success -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    RefreshButton(onRefresh)
                }
                items(state.data) { user ->
                    UserCard(user)
                }
            }
        }
    }
}

@Composable
private fun TodosContent(
    state: NetworkState<List<Todo>>,
    onRefresh: () -> Unit
) {
    when (state) {
        is NetworkState.Loading -> LoadingContent()
        is NetworkState.Error -> ErrorContent(state.message, onRefresh)
        is NetworkState.Success -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    RefreshButton(onRefresh)
                }
                items(state.data.take(20)) { todo ->
                    TodoCard(todo)
                }
            }
        }
    }
}

@Composable
private fun UrlConnectionContent(
    state: NetworkState<List<Post>>,
    onRefresh: () -> Unit
) {
    when (state) {
        is NetworkState.Loading -> LoadingContent()
        is NetworkState.Error -> ErrorContent(state.message, onRefresh)
        is NetworkState.Success -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "URLConnection Test",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Data fetched using java.net.HttpURLConnection instead of OkHttp.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item {
                    RefreshButton(onRefresh)
                }
                items(state.data.take(10)) { post ->
                    PostCard(post)
                }
            }
        }
    }
}

@Composable
private fun AsyncContent(
    state: NetworkState<List<Post>>,
    onRefresh: () -> Unit,
    client: OkHttpClient,
    gson: Gson
) {
    when (state) {
        is NetworkState.Loading -> LoadingContent()
        is NetworkState.Error -> ErrorContent(state.message, onRefresh)
        is NetworkState.Success -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Async OkHttp Test (enqueue)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Data fetched using OkHttp's async enqueue() method instead of synchronous execute().",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item {
                    RefreshButton(onRefresh)
                }
                item {
                    AsyncPostButton(client, gson)
                }
                items(state.data.take(10)) { post ->
                    PostCard(post)
                }
            }
        }
    }
}

@Composable
private fun ImagesContent(
    state: NetworkState<List<LoadedImage>>,
    onRefresh: () -> Unit
) {
    when (state) {
        is NetworkState.Loading -> LoadingContent()
        is NetworkState.Error -> ErrorContent(state.message, onRefresh)
        is NetworkState.Success -> {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Refresh button at top
                Button(
                    onClick = onRefresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Refresh Images")
                }

                // Image grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.data) { loadedImage ->
                        ImageCard(loadedImage)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageCard(loadedImage: LoadedImage) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (loadedImage.bitmap != null) {
                Image(
                    bitmap = loadedImage.bitmap.asImageBitmap(),
                    contentDescription = "Image by ${loadedImage.info.author}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = "Failed to load",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // Author overlay
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ) {
                Text(
                    text = loadedImage.info.author,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(4.dp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun AsyncPostButton(client: OkHttpClient, gson: Gson) {
    var isLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Column {
        Button(
            onClick = {
                isLoading = true
                result = null
                createPostAsync(client, gson) { response ->
                    result = response
                    isLoading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Create Post Async (enqueue)")
        }
        result?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading...")
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Error: $message",
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun RefreshButton(onRefresh: () -> Unit) {
    Button(
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Refresh Data")
    }
}

@Composable
private fun CreatePostButton(client: OkHttpClient, gson: Gson) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Column {
        Button(
            onClick = {
                isLoading = true
                result = null
                scope.launch {
                    result = createPost(client, gson)
                    isLoading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Create New Post (POST)")
        }
        result?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun PostCard(post: Post) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = post.title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = post.body,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Post #${post.id} by User #${post.userId}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun UserCard(user: User) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = user.name,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = "@${user.username}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Email: ${user.email}", fontSize = 14.sp)
            Text(text = "Phone: ${user.phone}", fontSize = 14.sp)
            Text(text = "Website: ${user.website}", fontSize = 14.sp)
        }
    }
}

@Composable
private fun TodoCard(todo: Todo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = todo.completed,
                onCheckedChange = null // Read-only
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = todo.title,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// Network functions
private suspend fun fetchPosts(client: OkHttpClient, gson: Gson): NetworkState<List<Post>> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://jsonplaceholder.typicode.com/posts")
                .header("Accept", "application/json")
                .header("X-Request-ID", "test-get-123")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<Post>>() {}.type
                    val posts: List<Post> = gson.fromJson(body, type)
                    NetworkState.Success(posts)
                } else {
                    NetworkState.Error("HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: Exception) {
            NetworkState.Error(e.message ?: "Unknown error")
        }
    }
}

private suspend fun fetchUsers(client: OkHttpClient, gson: Gson): NetworkState<List<User>> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://jsonplaceholder.typicode.com/users")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<User>>() {}.type
                    val users: List<User> = gson.fromJson(body, type)
                    NetworkState.Success(users)
                } else {
                    NetworkState.Error("HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: Exception) {
            NetworkState.Error(e.message ?: "Unknown error")
        }
    }
}

private suspend fun fetchTodos(client: OkHttpClient, gson: Gson): NetworkState<List<Todo>> {
    return withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://jsonplaceholder.typicode.com/todos")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<Todo>>() {}.type
                    val todos: List<Todo> = gson.fromJson(body, type)
                    NetworkState.Success(todos)
                } else {
                    NetworkState.Error("HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: Exception) {
            NetworkState.Error(e.message ?: "Unknown error")
        }
    }
}

private suspend fun createPost(client: OkHttpClient, gson: Gson): String {
    return withContext(Dispatchers.IO) {
        try {
            val newPost = mapOf(
                "title" to "Test Post from ADT Sidekick",
                "body" to "This is a test post to verify POST request capture with headers and body.",
                "userId" to 1
            )
            val jsonBody = gson.toJson(newPost)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://jsonplaceholder.typicode.com/posts")
                .header("Content-Type", "application/json")
                .header("X-Custom-Header", "test-value-123")
                .header("Authorization", "Bearer fake-token-for-testing")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    "Created! Response: $responseBody"
                } else {
                    "Error: HTTP ${response.code}"
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

/**
 * Fetches posts using java.net.HttpURLConnection instead of OkHttp.
 * This tests the URLConnection adapter in ADT Sidekick.
 */
private suspend fun fetchPostsWithUrlConnection(gson: Gson): NetworkState<List<Post>> {
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://jsonplaceholder.typicode.com/posts")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Client", "URLConnection-Test")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                val type = object : TypeToken<List<Post>>() {}.type
                val posts: List<Post> = gson.fromJson(response.toString(), type)
                NetworkState.Success(posts)
            } else {
                NetworkState.Error("HTTP $responseCode: ${connection.responseMessage}")
            }
        } catch (e: Exception) {
            NetworkState.Error(e.message ?: "Unknown error")
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * Fetches posts using OkHttp's async enqueue() method instead of execute().
 * This tests the async request capture in ADT Sidekick.
 */
private fun fetchPostsAsync(
    client: OkHttpClient,
    gson: Gson,
    callback: (NetworkState<List<Post>>) -> Unit
) {
    val request = Request.Builder()
        .url("https://jsonplaceholder.typicode.com/posts")
        .header("Accept", "application/json")
        .header("X-Request-Type", "async-enqueue")
        .header("X-Request-ID", "async-get-${System.currentTimeMillis()}")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(NetworkState.Error(e.message ?: "Unknown error"))
            }
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<Post>>() {}.type
                    val posts: List<Post> = gson.fromJson(body, type)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callback(NetworkState.Success(posts))
                    }
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callback(NetworkState.Error("HTTP ${response.code}: ${response.message}"))
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(NetworkState.Error(e.message ?: "Parse error"))
                }
            }
        }
    })
}

/**
 * Creates a new post using OkHttp's async enqueue() method.
 * This tests async POST request capture in ADT Sidekick.
 */
private fun createPostAsync(
    client: OkHttpClient,
    gson: Gson,
    callback: (String) -> Unit
) {
    val newPost = mapOf(
        "title" to "Async Test Post from ADT Sidekick",
        "body" to "This is a test post created using OkHttp's async enqueue() method.",
        "userId" to 1
    )
    val jsonBody = gson.toJson(newPost)
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = jsonBody.toRequestBody(mediaType)

    val request = Request.Builder()
        .url("https://jsonplaceholder.typicode.com/posts")
        .header("Content-Type", "application/json")
        .header("X-Request-Type", "async-enqueue")
        .header("X-Request-ID", "async-post-${System.currentTimeMillis()}")
        .post(requestBody)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback("Error: ${e.message}")
            }
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    "Created! Response: $responseBody"
                } else {
                    "Error: HTTP ${response.code}"
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(result)
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback("Error: ${e.message}")
                }
            }
        }
    })
}

/**
 * Fetches images from Picsum Photos API.
 * This tests image response capture in ADT Sidekick.
 */
private suspend fun fetchImages(client: OkHttpClient, gson: Gson): NetworkState<List<LoadedImage>> {
    return withContext(Dispatchers.IO) {
        try {
            // First, get the list of images from the API
            val listRequest = Request.Builder()
                .url("https://picsum.photos/v2/list?page=1&limit=20")
                .header("Accept", "application/json")
                .build()

            val images = client.newCall(listRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<PicsumImage>>() {}.type
                    gson.fromJson<List<PicsumImage>>(body, type)
                } else {
                    return@withContext NetworkState.Error("HTTP ${response.code}: ${response.message}")
                }
            }

            // Use only first 5 unique images but repeat them to test deduplication
            // This creates 20 requests but only 5 unique images should be stored
            val uniqueImages = images.take(5)
            val repeatedImages = (1..4).flatMap { uniqueImages }  // Repeat 4 times = 20 items

            val loadedImages = repeatedImages.mapIndexed { index, imageInfo ->
                try {
                    val imageUrl = "https://picsum.photos/id/${imageInfo.id}/200/200"
                    val imageRequest = Request.Builder()
                        .url(imageUrl)
                        .build()

                    val bitmap = client.newCall(imageRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.byteStream()?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        } else {
                            null
                        }
                    }
                    // Create unique LoadedImage with index to differentiate in UI
                    LoadedImage(imageInfo.copy(id = "${imageInfo.id}-$index"), bitmap)
                } catch (e: Exception) {
                    LoadedImage(imageInfo.copy(id = "${imageInfo.id}-$index"), null)
                }
            }

            NetworkState.Success(loadedImages)
        } catch (e: Exception) {
            NetworkState.Error(e.message ?: "Unknown error")
        }
    }
}
