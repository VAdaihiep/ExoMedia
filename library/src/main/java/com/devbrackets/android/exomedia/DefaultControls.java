/*
 * Copyright (C) 2015 Brian Wernick
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

package com.devbrackets.android.exomedia;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.DrawableRes;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.devbrackets.android.exomedia.event.EMMediaNextEvent;
import com.devbrackets.android.exomedia.event.EMMediaPlayPauseEvent;
import com.devbrackets.android.exomedia.event.EMMediaPreviousEvent;
import com.devbrackets.android.exomedia.event.EMMediaProgressEvent;
import com.devbrackets.android.exomedia.event.EMVideoViewControlVisibilityEvent;
import com.devbrackets.android.exomedia.listener.EMVideoViewControlsCallback;
import com.squareup.otto.Bus;

import java.util.Formatter;
import java.util.Locale;

/**
 * This is a simple abstraction for the EMVideoView to have a single "View" to add
 * or remove for the Default Video Controls.
 */
class DefaultControls extends RelativeLayout {
    private static final long CONTROL_VISIBILITY_ANIMATION_LENGTH = 300;

    private TextView currentTime;
    private TextView endTime;
    private SeekBar seekBar;
    private ImageButton playPauseButton;
    private ImageButton previousButton;
    private ImageButton nextButton;
    private ProgressBar loadingProgress;

    private StringBuilder formatBuilder;
    private Formatter formatter;
    private EMVideoViewControlsCallback callback;

    //Remember, 0 is not a valid resourceId
    private int playResourceId = 0;
    private int pauseResourceId = 0;

    private boolean previousButtonRemoved = true;
    private boolean nextButtonRemoved = true;

    private boolean pausedForSeek = false;
    private long hideDelay = -1;
    private boolean userInteracting = false;

    private boolean isVisible = true;
    private boolean canViewHide = true;
    private Handler visibilityHandler = new Handler();

    private EMVideoView videoView;
    private Bus bus;

    public DefaultControls(Context context) {
        super(context);
        setup(context);
    }

    public DefaultControls(Context context, AttributeSet attrs) {
        super(context, attrs);
        setup(context);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public DefaultControls(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setup(context);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public DefaultControls(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setup(context);
    }

    private void setup(Context context) {
        View.inflate(context, R.layout.exomedia_video_controls_overlay, this);

        formatBuilder = new StringBuilder();
        formatter = new Formatter(formatBuilder, Locale.getDefault());

        currentTime = (TextView) findViewById(R.id.exomedia_controls_current_time);
        endTime = (TextView) findViewById(R.id.exomedia_controls_end_time);
        seekBar = (SeekBar) findViewById(R.id.exomedia_controls_video_seek);
        playPauseButton = (ImageButton) findViewById(R.id.exomedia_controls_play_pause_btn);
        previousButton = (ImageButton) findViewById(R.id.exomedia_controls_previous_btn);
        nextButton = (ImageButton) findViewById(R.id.exomedia_controls_next_btn);
        loadingProgress = (ProgressBar) findViewById(R.id.exomedia_controls_video_loading);

        seekBar.setOnSeekBarChangeListener(new SeekBarChanged());

        playPauseButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlayPauseClick();
            }
        });
        previousButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onPreviousClick();
            }
        });
        nextButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onNextClick();
            }
        });
    }

    /**
     * Sets the bus to use for dispatching Events that correspond to the callbacks
     * listed in {@link com.devbrackets.android.exomedia.listener.EMVideoViewControlsCallback}
     *
     * @param bus The Otto bus to dispatch events on
     */
    void setBus(Bus bus) {
        this.bus = bus;
    }

    /**
     * Sets the parent view to use for determining playback length, position,
     * state, etc.  This should only be called once, during the setup process
     *
     * @param EMVideoView The Parent view to these controls
     */
    void setVideoView(EMVideoView EMVideoView) {
        this.videoView = EMVideoView;
    }

    /**
     * Specifies the callback to use for informing the host app of click events
     *
     * @param callback The callback
     */
    void setVideoViewControlsCallback(EMVideoViewControlsCallback callback) {
        this.callback = callback;
    }

    /**
     * Used to inform the controls to finalize their setup.  This
     * means replacing the loading animation with the PlayPause button
     */
    void loadCompleted() {
        playPauseButton.setVisibility(View.VISIBLE);
        previousButton.setVisibility(previousButtonRemoved ? View.INVISIBLE : View.VISIBLE);
        nextButton.setVisibility(nextButtonRemoved ? View.INVISIBLE : View.VISIBLE);
        loadingProgress.setVisibility(View.GONE);

        updatePlayPauseImage(videoView.isPlaying());
    }

    /**
     * Used to inform the controls to return to the loading stage.
     * This is the opposite of {@link #loadCompleted()}
     */
    void restartLoading() {
        playPauseButton.setVisibility(View.INVISIBLE);
        previousButton.setVisibility(View.INVISIBLE);
        nextButton.setVisibility(View.INVISIBLE);
        loadingProgress.setVisibility(View.VISIBLE);
    }

    /**
     * Sets the current video position, updating the seek bar
     * and the current time field
     *
     * @param position The position in milliseconds
     */
    void setPosition(long position) {
        currentTime.setText(formatTime(position));
        seekBar.setProgress((int) position);
    }

    /**
     * Sets the video duration in Milliseconds to display
     * at the end of the progress bar
     *
     * @param duration The duration of the video in milliseconds
     */
    void setDuration(long duration) {
        endTime.setText(formatTime(duration));
        seekBar.setMax((int) duration);
    }

    /**
     * Performs the progress update on the current time field,
     * and the seek bar
     *
     * @param event The most recent progress
     */
    void setProgressEvent(EMMediaProgressEvent event) {
        if (!userInteracting) {
            seekBar.setSecondaryProgress((int) (seekBar.getMax() * event.getBufferPercentFloat()));
            seekBar.setProgress((int)event.getPosition());
            currentTime.setText(formatTime(event.getPosition()));
        }
    }

    /**
     * Sets the resource id's to use for the PlayPause button.
     *
     * @param playResourceId  The resourceId or 0
     * @param pauseResourceId The resourceId or 0
     */
    void setPlayPauseImages(@DrawableRes int playResourceId, @DrawableRes int pauseResourceId) {
        this.playResourceId = playResourceId;
        this.pauseResourceId = pauseResourceId;
    }

    /**
     * Sets the state list drawable resource id to use for the Previous button.
     *
     * @param resourceId The resourceId or 0
     */
    void setPreviousImageResource(@DrawableRes int resourceId) {
        previousButton.setImageResource(resourceId != 0 ? resourceId : R.drawable.exomedia_video_previous);
    }

    /**
     * Sets the state list drawable resource id to use for the Next button.
     *
     * @param resourceId The resourceId or 0
     */
    void setNextImageResource(@DrawableRes int resourceId) {
        nextButton.setImageResource(resourceId != 0 ? resourceId : R.drawable.exomedia_video_next);
    }

    /**
     * Makes sure the playPause button represents the correct playback state
     *
     * @param isPlaying If the video is currently playing
     */
    void updatePlayPauseImage(boolean isPlaying) {
        if (isPlaying) {
            playPauseButton.setImageResource(pauseResourceId != 0 ? pauseResourceId : R.drawable.exomedia_ic_pause_white);
        } else {
            playPauseButton.setImageResource(playResourceId != 0 ? playResourceId : R.drawable.exomedia_ic_play_arrow_white);
        }
    }

    /**
     * Sets the button state for the Previous button.  This will just
     * change the images specified with {@link #setPreviousImageResource(int)},
     * or use the defaults if they haven't been set, and block any click events.
     * </p>
     * This method will NOT re-add buttons that have previously been removed with
     * {@link #setNextButtonRemoved(boolean)}.
     *
     * @param enabled If the Previous button is enabled [default: false]
     */
    void setPreviousButtonEnabled(boolean enabled) {
        //The tag is used in the onClick methods to determine if they should perform callbacks or post events
        previousButton.setTag(enabled);
        previousButton.setEnabled(enabled);
    }

    /**
     * Sets the button state for the Next button.  This will just
     * change the images specified with {@link #setNextImageResource(int)},
     * or use the defaults if they haven't been set, and block any click events.
     * </p>
     * This method will NOT re-add buttons that have previously been removed with
     * {@link #setPreviousButtonRemoved(boolean)}.
     *
     * @param enabled If the Next button is enabled [default: false]
     */
    void setNextButtonEnabled(boolean enabled) {
        //The tag is used in the onClick methods to determine if they should perform callbacks or post events
        nextButton.setTag(enabled);
        nextButton.setEnabled(enabled);
    }

    /**
     * Adds or removes the Previous button.  This will change the visibility
     * of the button, if you want to change the enabled/disabled images see {@link #setPreviousButtonEnabled(boolean)}
     *
     * @param removed If the Previous button should be removed [default: true]
     */
    public void setPreviousButtonRemoved(boolean removed) {
        previousButton.setVisibility(removed ? View.INVISIBLE : View.VISIBLE);
        previousButtonRemoved = removed;
    }

    /**
     * Adds or removes the Next button.  This will change the visibility
     * of the button, if you want to change the enabled/disabled images see {@link #setNextButtonEnabled(boolean)}
     *
     * @param removed If the Next button should be removed [default: true]
     */
    public void setNextButtonRemoved(boolean removed) {
        nextButton.setVisibility(removed ? View.INVISIBLE : View.VISIBLE);
        nextButtonRemoved = removed;
    }

    /**
     * Immediately starts the animation to show the controls
     */
    void show() {
        //Makes sure we don't have a hide animation scheduled
        visibilityHandler.removeCallbacksAndMessages(null);
        clearAnimation();

        animateVisibility(true);
    }

    /**
     * After the specified delay the view will be hidden.  If the user is interacting
     * with the controls then we wait until after they are done to start the delay.
     *
     * @param delay The delay in milliseconds to wait to start the hide animation
     */
    void hideDelayed(long delay) {
        hideDelay = delay;

        if (delay < 0 || !canViewHide) {
            return;
        }

        //If the user is interacting with controls we don't want to start the delayed hide yet
        if (userInteracting) {
            return;
        }

        visibilityHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                animateVisibility(false);
            }
        }, delay);
    }

    /**
     * Sets weather this control can be hidden.
     *
     * @param canHide If this control can be hidden [default: true]
     */
    void setCanHide(boolean canHide) {
        canViewHide = canHide;
    }

    /**
     * Performs the functionality when the PlayPause button is clicked.  This
     * includes invoking the callback method if it is enabled, posting the bus
     * event, and toggling the video playback.
     */
    private void onPlayPauseClick() {
        if (callback != null && callback.onPlayPauseClicked()) {
            return;
        }

        if (bus != null) {
            bus.post(new EMMediaPlayPauseEvent());
        }

        //toggles the playback
        boolean playing = videoView.isPlaying();
        if (playing) {
            videoView.pause();
        } else {
            videoView.start();
        }

        updatePlayPauseImage(!playing);
    }

    private void onPreviousClick() {
        //If the tag is null or if the button is not enabled, then don't inform anything
        if (previousButton.getTag() == null || !(Boolean)previousButton.getTag()) {
            return;
        }

        if (callback != null && callback.onPreviousClicked()) {
            return;
        }

        if (bus != null) {
            bus.post(new EMMediaPreviousEvent());
        }
    }

    private void onNextClick() {
        //If the tag is null or if the button is not enabled, then don't inform anything
        if (nextButton.getTag() == null || !(Boolean)nextButton.getTag()) {
            return;
        }

        if (callback != null && callback.onNextClicked()) {
            return;
        }

        if (bus != null) {
            bus.post(new EMMediaNextEvent());
        }
    }

    /**
     * Performs the functionality to inform the callback and post bus events
     * that the DefaultControls visibility has changed
     */
    private void onVisibilityChanged() {
        boolean handled = false;
        if (callback != null) {
            if (isVisible) {
                handled = callback.onControlsShown();
            } else {
                handled = callback.onControlsHidden();
            }
        }

        if (!handled && bus != null) {
            bus.post(new EMVideoViewControlVisibilityEvent(isVisible));
        }
    }

    /**
     * Formats the specified millisecond time to a human readable format
     * in the form of (Hours : Minutes : Seconds)
     *
     * @param time The time in milliseconds to format
     * @return The human readable time
     */
    private String formatTime(long time) {
        long seconds = (time % DateUtils.MINUTE_IN_MILLIS) / DateUtils.SECOND_IN_MILLIS;
        long minutes = (time % DateUtils.HOUR_IN_MILLIS) / DateUtils.MINUTE_IN_MILLIS;
        long hours = (time % DateUtils.DAY_IN_MILLIS) / DateUtils.HOUR_IN_MILLIS;

        formatBuilder.setLength(0);
        if (hours > 0) {
            return formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        }

        return formatter.format("%02d:%02d", minutes, seconds).toString();
    }

    /**
     * Performs the control visibility animation for showing or hiding
     * this view
     *
     * @param toVisible True if the view should be visible at the end of the animation
     */
    private void animateVisibility(boolean toVisible) {
        if (isVisible == toVisible) {
            return;
        }

        float startAlpha = toVisible ? 0 : 1;
        float endAlpha = toVisible ? 1 : 0;

        AlphaAnimation animation = new AlphaAnimation(startAlpha, endAlpha);
        animation.setDuration(CONTROL_VISIBILITY_ANIMATION_LENGTH);
        animation.setFillAfter(true);
        startAnimation(animation);

        isVisible = toVisible;
        onVisibilityChanged();
    }

    /**
     * Listens to the seek bar change events and correctly handles the changes
     */
    private class SeekBarChanged implements SeekBar.OnSeekBarChangeListener {
        private int seekToTime;

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser) {
                return;
            }

            seekToTime = progress;

            if (currentTime != null) {
                currentTime.setText(formatTime(progress));
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            userInteracting = true;

            if (videoView.isPlaying()) {
                pausedForSeek = true;
                videoView.pause();
            }

            //Make sure to keep the controls visible during seek
            show();
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            userInteracting = false;
            videoView.seekTo(seekToTime);

            if (pausedForSeek) {
                pausedForSeek = false;
                videoView.start();
                hideDelayed(hideDelay);
            }
        }
    }
}