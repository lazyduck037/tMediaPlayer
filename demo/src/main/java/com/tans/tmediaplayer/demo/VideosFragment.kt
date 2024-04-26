package com.tans.tmediaplayer.demo

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.tans.tmediaplayer.demo.databinding.VideoItemLayoutBinding
import com.tans.tmediaplayer.demo.databinding.VideosFragmentBinding
import com.tans.tmediaplayer.demo.glide.MediaImageModel
import com.tans.tuiutils.adapter.impl.builders.SimpleAdapterBuilderImpl
import com.tans.tuiutils.adapter.impl.databinders.DataBinderImpl
import com.tans.tuiutils.adapter.impl.datasources.FlowDataSourceImpl
import com.tans.tuiutils.adapter.impl.viewcreatators.SingleItemViewCreatorImpl
import com.tans.tuiutils.dialog.dp2px
import com.tans.tuiutils.fragment.BaseCoroutineStateFragment
import com.tans.tuiutils.mediastore.MediaStoreVideo
import com.tans.tuiutils.mediastore.queryVideoFromMediaStore
import com.tans.tuiutils.view.clicks
import com.tans.tuiutils.view.refreshes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VideosFragment : BaseCoroutineStateFragment<VideosFragment.Companion.State>(State()) {

    override val layoutId: Int = R.layout.videos_fragment

    override fun CoroutineScope.firstLaunchInitDataCoroutine() {
        launch { refreshVideos() }
    }

    override fun CoroutineScope.bindContentViewCoroutine(contentView: View) {
        val viewBinding = VideosFragmentBinding.bind(contentView)
        viewBinding.refreshLayout.refreshes(this, Dispatchers.IO) {
            refreshVideos()
        }
        val adapter = SimpleAdapterBuilderImpl<MediaStoreVideo>(
            itemViewCreator = SingleItemViewCreatorImpl(R.layout.video_item_layout),
            dataSource = FlowDataSourceImpl(stateFlow().map { it.videos }),
            dataBinder = DataBinderImpl { data, view, _ ->
                val itemViewBinding = VideoItemLayoutBinding.bind(view)
                itemViewBinding.videoTitleTv.text = data.displayName
                itemViewBinding.videoDurationTv.text = data.duration.formatDuration()
                Glide.with(this@VideosFragment)
                    .load(MediaImageModel(data.file?.canonicalPath ?: "", 10000L))
                    .error(R.drawable.ic_movie)
                    .placeholder(R.drawable.ic_movie)
                    .into(itemViewBinding.videoIv)

                itemViewBinding.root.clicks(this) {
                    startActivity(PlayerActivity.createIntent(requireActivity(), data.file?.canonicalPath ?: ""))
                }
            }
        ).build()
        viewBinding.videosRv.adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.videosRv) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom + requireContext().dp2px(8))
            insets
        }
    }

    private fun refreshVideos() {
        val videos = queryVideoFromMediaStore().sortedByDescending { it.dateModified }.filter { it.file != null }
        updateState {
            it.copy(videos = videos)
        }
    }

    companion object {
        data class State(
            val videos: List<MediaStoreVideo> = emptyList()
        )
    }
}