/*
 * Copyright (C) 2020 Flipkart Internet Pvt Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flipkart.madman.renderer

import android.content.Intent
import android.net.Uri
import android.os.CountDownTimer
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.flipkart.madman.helper.Utils
import com.flipkart.madman.manager.helper.Constant
import com.flipkart.madman.manager.model.AdElement
import com.flipkart.madman.renderer.binder.AdViewBinder
import com.flipkart.madman.renderer.binder.AdViewHolder
import com.flipkart.madman.renderer.callback.ViewClickListener
import com.flipkart.madman.renderer.player.AdPlayer
import com.flipkart.madman.renderer.settings.DefaultRenderingSettings
import com.flipkart.madman.renderer.settings.RenderingSettings
import java.util.concurrent.TimeUnit

/**
 * Default implementation of [AdRenderer]
 */
open class DefaultAdRenderer private constructor(
    builder: Builder,
    private val viewBinder: AdViewBinder
) : AdRenderer {

    private var view: View? = null

    /** ad player interface to interact with the underlying player **/
    private val player: AdPlayer

    /** container to add ad overlays **/
    private val container: ViewGroup

    /** ad rendering settings **/
    private val renderingSettings: RenderingSettings

    /** count down timer to show skip ad if skippable **/
    private var skipCountDownTimer: CountDownTimer? = null

    private val viewHolders: MutableMap<View, AdViewHolder> = mutableMapOf()

    /** registered view callbacks **/
    val viewClickListeners: MutableList<ViewClickListener> = mutableListOf()

    init {
        player = builder.player
            ?: throw IllegalStateException("ad player not set, call setPlayer on Builder")
        container = builder.container
            ?: throw IllegalStateException("container not set, call setContainer on Builder")
        renderingSettings = builder.renderingSettings ?: DefaultRenderingSettings()
    }

    override fun getAdPlayer(): AdPlayer {
        return player
    }

    override fun createView() {
        val view = LayoutInflater.from(container.context)
            .inflate(viewBinder.layoutToInflateId, container, false)
        var adViewHolder = viewHolders[view]
        if (adViewHolder == null) {
            adViewHolder = AdViewHolder().from(view, viewBinder)
            viewHolders[view] = adViewHolder
        }
        this.view = view
        container.addView(view)
    }

    override fun renderView(adElement: AdElement) {
        view?.let {
            toggleViewVisibility(true)

            val adViewHolder = viewHolders[it]

            /** attach listener on the whole view **/
            it.setOnClickListener {
                for (viewCallback in viewClickListeners) {
                    viewCallback.onAdViewClick()
                }
            }

            /** configure ad count down view **/
            configureAdCountDownView(adViewHolder?.adCountDownView, adElement.getDuration())

            /** configure skip ad view **/
            configureSkipAdView(
                adViewHolder?.skipView,
                adElement.canSkip(),
                adElement.getSkipOffset(),
                adElement.getDuration()
            )

            /** configure click through view **/
            configureClickThroughView(
                adViewHolder?.clickThroughView,
                adElement.getClickThroughUrl()
            ) { url ->
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)

                val chooser = Intent.createChooser(intent, "Open with")
                if (intent.resolveActivity(container.context.packageManager) != null) {
                    container.context.startActivity(chooser)
                }
            }
        }
    }

    /**
     * configure the skip ad view
     *
     * @param view the skip ad text view
     * @param canSkip if the ad can be skipped
     * @param skipOffset when to skip the ad
     * @param duration of the ad
     */
    protected open fun configureSkipAdView(
        view: TextView?,
        canSkip: Boolean,
        skipOffset: Double,
        duration: Double
    ) {
        if (canSkip) {
            /** attach listener for skip ad view **/
            view?.setOnClickListener {
                for (viewCallback in viewClickListeners) {
                    viewCallback.onSkipAdClick()
                }
            }
            cancelSkipTimer()
            view?.visibility = View.VISIBLE
            view?.isEnabled = false
            skipCountDownTimer =
                object : CountDownTimer(
                    (duration - skipOffset).toLong() * Constant.MILLISECOND_MULTIPLIER + 150,
                    COUNT_DOWN_INTERVAL
                ) {
                    override fun onFinish() {
                        view?.text = SKIP_AD_TEXT
                        view?.isEnabled = true
                    }

                    override fun onTick(millisUntilFinished: Long) {
                        val seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished)
                        if (seconds > 0) {
                            view?.text = String.format(SKIP_AD_STARTING_TEXT_PLACEHOLDER, seconds)
                        }
                    }
                }
            skipCountDownTimer?.start()
        } else {
            view?.visibility = View.GONE
        }
    }

    /**
     * configure the click through view
     *
     * @param view the learn more view
     * @param url the click through url
     * @param onClick callback when the view is clicked
     */
    protected open fun configureClickThroughView(
        view: TextView?,
        url: String?,
        onClick: (url: String?) -> Unit
    ) {
        if (!TextUtils.isEmpty(url)) {
            /** url is not empty, configure view **/
            view?.visibility = View.VISIBLE
            view?.text = LEARN_MORE_TEXT
            view?.setOnClickListener {
                onClick(url)
                for (viewCallback in viewClickListeners) {
                    viewCallback.onClickThroughClick()
                }
            }
        } else {
            view?.visibility = View.GONE
        }
    }

    /**
     * configure the ad count down view
     *
     * @param view the ad count down view
     * @param duration of the ad
     */
    protected open fun configureAdCountDownView(view: TextView?, duration: Double) {
        view?.visibility = View.VISIBLE
    }

    override fun onAdProgressUpdate(progress: Float, duration: Float) {
        view?.let {
            val adViewHolder = viewHolders[it]
            val seconds = TimeUnit.SECONDS.toSeconds((duration - progress).toLong())
            adViewHolder?.adCountDownView?.text =
                String.format(AD_STARTING_IN_PLACEHOLDER, Utils.formatSecondsToMMSS(seconds))
        }
    }

    override fun getRenderingSettings(): RenderingSettings {
        return renderingSettings
    }

    override fun registerViewClickListener(clickListener: ViewClickListener) {
        viewClickListeners.add(clickListener)
    }

    override fun unregisterViewClickListener(clickListener: ViewClickListener) {
        viewClickListeners.remove(clickListener)
    }

    override fun destroy() {
        cancelSkipTimer()
        /** clear all maps **/
        viewClickListeners.clear()
        viewHolders.clear()
    }

    override fun removeView() {
        container.removeView(view)
    }

    private fun toggleViewVisibility(show: Boolean) {
        if (show) {
            view?.visibility = View.VISIBLE
        } else {
            view?.visibility = View.GONE
        }
    }

    /**
     * cancel the skip timer
     */
    private fun cancelSkipTimer() {
        skipCountDownTimer?.cancel()
        skipCountDownTimer = null
    }

    class Builder {
        internal var renderingSettings: RenderingSettings? = null
        internal var player: AdPlayer? = null
        internal var container: ViewGroup? = null

        fun setPlayer(player: AdPlayer): Builder {
            this.player = player
            return this
        }

        fun setContainer(container: ViewGroup): Builder {
            this.container = container
            return this
        }

        fun setRenderingSettings(settings: RenderingSettings): Builder {
            this.renderingSettings = settings
            return this
        }

        fun build(viewBinder: AdViewBinder?): DefaultAdRenderer {
            return DefaultAdRenderer(this, viewBinder ?: AdViewBinder.Builder().build())
        }
    }

    companion object {
        const val SKIP_AD_TEXT = "Skip Ad"
        const val SKIP_AD_STARTING_TEXT_PLACEHOLDER = "You can skip ad in %d"
        const val AD_STARTING_IN_PLACEHOLDER = "Ad ending in %s"
        const val LEARN_MORE_TEXT = "Learn more"

        const val COUNT_DOWN_INTERVAL = 1000L
    }
}
