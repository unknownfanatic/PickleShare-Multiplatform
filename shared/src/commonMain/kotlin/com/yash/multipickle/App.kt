package com.yash.multipickle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.yash.multipickle.ui.AppEffect
import com.yash.multipickle.ui.AppEvent
import com.yash.multipickle.ui.MainScreen
import com.yash.multipickle.ui.MainViewModel
import com.yash.multipickle.ui.theme.PickleShareTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.openFilePicker
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun App(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = koinViewModel()
) {
    PickleShareTheme {
        val state by viewModel.uiState.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.sideEffect.collect {
                when (it) {
                    AppEffect.OnSelectFiles -> {
                        val files = FileKit.openFilePicker(mode = FileKitMode.Multiple())
                        viewModel.handleEvent(AppEvent.OnFilesSelected(files))
                    }
                }
            }
        }
        MainScreen(state = state, handleEvent = viewModel::handleEvent, modifier = modifier)
    }
}
