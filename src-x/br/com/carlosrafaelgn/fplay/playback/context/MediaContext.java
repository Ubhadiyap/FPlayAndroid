//
// FPlayAndroid is distributed under the FreeBSD License
//
// Copyright (c) 2013-2014, Carlos Rafael Gimenes das Neves
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
// ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// The views and conclusions contained in the software and documentation are those
// of the authors and should not be interpreted as representing official policies,
// either expressed or implied, of the FreeBSD Project.
//
// https://github.com/carlosrafaelgn/FPlayAndroid
//
package br.com.carlosrafaelgn.fplay.playback.context;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;

import br.com.carlosrafaelgn.fplay.playback.Player;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class MediaContext implements Runnable, Handler.Callback {
	private static final int MSG_COMPLETION = 0x0100;
	private static final int MSG_ERROR = 0x0101;
	private static final int MSG_SEEKCOMPLETE = 0x0102;
	private static final int MSG_BUFFERINGSTART = 0x0103;
	private static final int MSG_BUFFERINGEND = 0x0104;

	private static final int ACTION_NONE = 0x0000;
	private static final int ACTION_PLAY = 0x0001;
	private static final int ACTION_PAUSE = 0x0002;
	private static final int ACTION_RESUME = 0x0003;
	private static final int ACTION_SEEK = 0x0004;
	private static final int ACTION_SETNEXT = 0x0005;
	private static final int ACTION_RESET = 0x0006;
	private static final int ACTION_INITIALIZE = 0xFFFF;

	private static final int PLAYER_TIMEOUT = 30000;

	static final int SL_MILLIBEL_MIN = -32768;

	private static final class ErrorStructure {
		public MediaCodecPlayer player;
		public Throwable exception;

		public ErrorStructure(MediaCodecPlayer player, Throwable exception) {
			this.player = player;
			this.exception = exception;
		}
	}

	private static final Object threadNotification = new Object();
	private static final Object notification = new Object();
	private static final Object openSLSync = new Object();
	private static volatile boolean alive, waitToReceiveAction, requestSucceeded, initializationError;
	private static volatile int requestedAction, requestedSeekMS;
	private static int volumeInMillibels = 0;
	private static Handler handler;
	private static Thread thread;
	private static volatile MediaCodecPlayer playerRequestingAction, nextPlayerRequested;
	private static MediaContext theMediaContext;

	static {
		System.loadLibrary("MediaContextJni");
	}

	private static native void resetFiltersAndWritePosition(int srcChannelCount);

	static native void enableEqualizer(int enabled);
	static native int isEqualizerEnabled();
	static native void setEqualizerBandLevel(int band, int level);
	static native void setEqualizerBandLevels(short[] levels);

	static native void enableBassBoost(int enabled);
	static native int isBassBoostEnabled();
	static native void setBassBoostStrength(int strength);
	static native int getBassBoostRoundedStrength();

	static native void enableVirtualizer(int enabled);
	static native int isVirtualizerEnabled();
	static native void setVirtualizerStrength(int strength);
	static native int getVirtualizerRoundedStrength();

	static native long startVisualization();
	static native long getVisualizationPtr();
	static native void stopVisualization();

	static native int openSLInitialize(int bufferSizeInFrames);
	static native int openSLCreate(int sampleRate);
	static native int openSLPlay();
	static native int openSLPause();
	static native int openSLStopAndFlush();
	static native void openSLRelease();
	static native void openSLTerminate();
	static native void openSLSetVolumeInMillibels(int volumeInMillibels);
	static native int openSLGetHeadPositionInFrames();
	static native int openSLWriteDirect(ByteBuffer buffer, int offsetInBytes, int sizeInBytes, int needsSwap);
	static native int openSLWrite(byte[] buffer, int offsetInBytes, int sizeInBytes, int needsSwap);

	private MediaContext() {
	}

	@SuppressWarnings("deprecation")
	private static int getBufferSizeInFrames() {
		int bufferSizeInFrames = 0;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
			try {
				final AudioManager am = (AudioManager)Player.theApplication.getSystemService(Context.AUDIO_SERVICE);
				bufferSizeInFrames = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
			} catch (Throwable ex) {
				//just ignore
			}
		}
		if (bufferSizeInFrames <= 0) {
			final int bufferSizeInBytes = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
			bufferSizeInFrames = bufferSizeInBytes >> 2;
		}

		final int oneSecondInFrames = 48000;
		//make sure it is a multiple of bufferSizeInFrames
		return ((oneSecondInFrames + bufferSizeInFrames - 1) / bufferSizeInFrames) * bufferSizeInFrames;
	}

	private static void checkOpenSLResult(int result) {
		if (result == 0)
			return;
		if (result < 0)
			result = -result;
		throw new IllegalStateException("openSL returned " + result);
	}

	@Override
	public void run() {
		thread.setPriority(Thread.MAX_PRIORITY);

		int sampleRate = 0;
		PowerManager.WakeLock wakeLock;
		MediaCodecPlayer currentPlayer = null, nextPlayer = null, sourcePlayer = null;
		MediaCodecPlayer.OutputBuffer outputBuffer = new MediaCodecPlayer.OutputBuffer();
		outputBuffer.index = -1;
		int lastHeadPositionInFrames = 0;
		long framesWritten = 0, framesPlayed = 0, nextFramesWritten = 0;

		synchronized (openSLSync) {
			initializationError = (openSLInitialize(getBufferSizeInFrames()) != 0);
		}

		if (initializationError) {
			requestedAction = ACTION_NONE;
			synchronized (notification) {
				notification.notify();
			}
			return;
		}

		MediaCodecPlayer.tryToProcessNonDirectBuffers = true;

		wakeLock = ((PowerManager)Player.theApplication.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "MediaContext WakeLock");
		wakeLock.setReferenceCounted(false);

		requestedAction = ACTION_NONE;

		synchronized (notification) {
			notification.notify();
		}

		boolean paused = true, playPending = false;
		while (alive) {
			if (paused || waitToReceiveAction) {
				synchronized (threadNotification) {
					if (requestedAction == ACTION_NONE) {
						try {
							threadNotification.wait();
						} catch (Throwable ex) {
							//just ignore
						}
					}

					if (!alive)
						break;

					if (requestedAction != ACTION_NONE) {
						waitToReceiveAction = false;
						MediaCodecPlayer seekPendingPlayer = null;
						requestSucceeded = false;
						try {
							//**** before calling notification.notify() we can safely assume the player thread
							//is halted while these actions take place, therefore the player objects are not
							//being used during this period
							switch (requestedAction) {
							case ACTION_PLAY:
								checkOpenSLResult(openSLStopAndFlush());
								outputBuffer.release();
								currentPlayer = playerRequestingAction;
								nextPlayer = null;
								sourcePlayer = currentPlayer;
								currentPlayer.resetDecoderIfOutputAlreadyUsed();
								framesWritten = currentPlayer.getCurrentPositionInFrames();
								framesPlayed = currentPlayer.getCurrentPositionInFrames();
								nextFramesWritten = 0;
								synchronized (openSLSync) {
									if (sampleRate != currentPlayer.getSampleRate()) {
										sampleRate = currentPlayer.getSampleRate();
										synchronized (openSLSync) {
											checkOpenSLResult(openSLCreate(sampleRate));
											openSLSetVolumeInMillibels(volumeInMillibels);
											MediaCodecPlayer.tryToProcessNonDirectBuffers = true;
										}
									}
								}
								playPending = true;
								bufferingStart(currentPlayer);
								resetFiltersAndWritePosition(currentPlayer.getChannelCount());
								lastHeadPositionInFrames = openSLGetHeadPositionInFrames();
								paused = false;
								requestSucceeded = true;
								wakeLock.acquire();
								break;
							case ACTION_PAUSE:
								if (playerRequestingAction == currentPlayer) {
									checkOpenSLResult(openSLPause());
									paused = true;
									requestSucceeded = true;
									wakeLock.release();
								} else {
									throw new IllegalStateException("impossible to pause a player other than currentPlayer");
								}
								break;
							case ACTION_RESUME:
								if (playerRequestingAction == currentPlayer) {
									if ((framesWritten - framesPlayed) < 512) {
										playPending = true;
										bufferingStart(currentPlayer);
									} else {
										checkOpenSLResult(openSLPlay());
									}
									paused = false;
									requestSucceeded = true;
									wakeLock.acquire();
								} else {
									throw new IllegalStateException("impossible to resume a player other than currentPlayer");
								}
								break;
							case ACTION_SEEK:
								if (currentPlayer != null && currentPlayer != playerRequestingAction)
									throw new IllegalStateException("impossible to seek a player other than currentPlayer");
								if (!paused)
									throw new IllegalStateException("trying to seek while not paused");
								checkOpenSLResult(openSLStopAndFlush());
								seekPendingPlayer = playerRequestingAction;
								requestSucceeded = true;
								break;
							case ACTION_SETNEXT:
								if (currentPlayer == playerRequestingAction && nextPlayer != nextPlayerRequested) {
									//if we had already started outputting nextPlayer's audio then it is too
									//late... just remove the nextPlayer
									if (currentPlayer.isOutputOver()) {
										//go back to currentPlayer
										if (sourcePlayer == nextPlayer) {
											outputBuffer.release();
											sourcePlayer = currentPlayer;
										}
										nextPlayer = null;
										nextFramesWritten = 0;
									} else {
										nextPlayer = nextPlayerRequested;
										try {
											if (nextPlayer != null) {
												if (currentPlayer.isInternetStream() ||
													nextPlayer.isInternetStream() ||
													sampleRate != nextPlayer.getSampleRate()) {
													nextPlayer = null;
													nextFramesWritten = 0;
												}
												if (nextPlayer != null)
													nextPlayer.resetDecoderIfOutputAlreadyUsed();
											}
										} catch (Throwable ex) {
											nextPlayer = null;
											nextFramesWritten = 0;
											handler.sendMessageAtTime(Message.obtain(handler, MSG_ERROR, new ErrorStructure(nextPlayer, ex)), SystemClock.uptimeMillis());
										}
									}
								}
								break;
							case ACTION_RESET:
								if (playerRequestingAction == currentPlayer) {
									//releasing prevents clicks when changing tracks
									synchronized (openSLSync) {
										openSLRelease();
										sampleRate = 0;
									}
									outputBuffer.release();
									paused = true;
									playPending = false;
									currentPlayer = null;
									nextPlayer = null;
									sourcePlayer = null;
									framesWritten = 0;
									framesPlayed = 0;
									nextFramesWritten = 0;
									wakeLock.release();
								} else if (playerRequestingAction == nextPlayer) {
									//go back to currentPlayer
									if (sourcePlayer == nextPlayer) {
										outputBuffer.release();
										sourcePlayer = currentPlayer;
									}
									nextPlayer = null;
									nextFramesWritten = 0;
								}
								requestSucceeded = true;
								break;
							}
						} catch (Throwable ex) {
							synchronized (openSLSync) {
								openSLRelease();
								sampleRate = 0;
							}
							try {
								outputBuffer.release();
							} catch (Throwable ex2) {
								//just ignore
							}
							paused = true;
							playPending = false;
							currentPlayer = null;
							nextPlayer = null;
							sourcePlayer = null;
							framesWritten = 0;
							framesPlayed = 0;
							nextFramesWritten = 0;
							wakeLock.release();
							handler.sendMessageAtTime(Message.obtain(handler, MSG_ERROR, new ErrorStructure(playerRequestingAction, ex)), SystemClock.uptimeMillis());
							continue;
						} finally {
							requestedAction = ACTION_NONE;
							playerRequestingAction = null;
							synchronized (notification) {
								notification.notify();
							}
						}
						if (seekPendingPlayer != null) {
							try {
								outputBuffer.release();
								resetFiltersAndWritePosition(seekPendingPlayer.getChannelCount());
								lastHeadPositionInFrames = openSLGetHeadPositionInFrames();
								if (nextPlayer != null) {
									try {
										nextPlayer.resetDecoderIfOutputAlreadyUsed();
									} catch (Throwable ex) {
										nextPlayer = null;
										handler.sendMessageAtTime(Message.obtain(handler, MSG_ERROR, new ErrorStructure(nextPlayer, ex)), SystemClock.uptimeMillis());
									}
									nextFramesWritten = 0;
								}
								seekPendingPlayer.doSeek(requestedSeekMS);
								//give the decoder some time to decode something
								try {
									Thread.sleep(30);
								} catch (Throwable ex) {
									//just ignore
								}
								handler.sendMessageAtTime(Message.obtain(handler, MSG_SEEKCOMPLETE, seekPendingPlayer), SystemClock.uptimeMillis());
								framesWritten = seekPendingPlayer.getCurrentPositionInFrames();
								framesPlayed = framesWritten;
							} catch (Throwable ex) {
								synchronized (openSLSync) {
									openSLRelease();
									sampleRate = 0;
								}
								try {
									outputBuffer.release();
								} catch (Throwable ex2) {
									//just ignore
								}
								paused = true;
								playPending = false;
								currentPlayer = null;
								nextPlayer = null;
								sourcePlayer = null;
								framesWritten = 0;
								framesPlayed = 0;
								nextFramesWritten = 0;
								wakeLock.release();
								handler.sendMessageAtTime(Message.obtain(handler, MSG_ERROR, new ErrorStructure(seekPendingPlayer, ex)), SystemClock.uptimeMillis());
								continue;
							}
						}
					}
				}
			}

			try {
				if (paused)
					continue;

				final int currentHeadPositionInFrames = openSLGetHeadPositionInFrames();
				framesPlayed += (currentHeadPositionInFrames - lastHeadPositionInFrames);
				lastHeadPositionInFrames = currentHeadPositionInFrames;

				currentPlayer.setCurrentPosition((int)((framesPlayed * 1000L) / (long)sampleRate));

				if (outputBuffer.index < 0)
					sourcePlayer.nextOutputBuffer(outputBuffer);
				if (outputBuffer.remainingBytes > 0) {
					final int bytesWrittenThisTime;
					if (MediaCodecPlayer.isDirect)
						bytesWrittenThisTime = openSLWriteDirect(outputBuffer.byteBuffer, outputBuffer.offsetInBytes, outputBuffer.remainingBytes, MediaCodecPlayer.needsSwap);
					else
						throw new UnsupportedOperationException("NOT DIRECT!!!");
					if (bytesWrittenThisTime < 0) {
						throw new IOException("audioTrackWriteDirect() returned " + bytesWrittenThisTime);
					} else if (bytesWrittenThisTime == 0) {
						if (playPending) {
							//we have just filled the buffer, time to start playing
							playPending = false;
							checkOpenSLResult(openSLPlay());
							bufferingEnd(sourcePlayer);
						}
						//the buffer was too full, just wait some time
						try {
							synchronized (threadNotification) {
								threadNotification.wait(30);
							}
							continue;
						} catch (Throwable ex) {
							//just ignore
						}
					} else {
						if (sourcePlayer == currentPlayer)
							framesWritten += bytesWrittenThisTime >> sourcePlayer.getChannelCount();
						else
							nextFramesWritten += bytesWrittenThisTime >> sourcePlayer.getChannelCount();

						outputBuffer.remainingBytes -= bytesWrittenThisTime;
						outputBuffer.offsetInBytes += bytesWrittenThisTime;
					}
				} else if (playPending && currentPlayer.isOutputOver()) {
					//the song ended before we had a chance to start playing before, so do it now!
					playPending = false;
					checkOpenSLResult(openSLPlay());
					bufferingEnd(sourcePlayer);
				}

				if (outputBuffer.remainingBytes <= 0) {
					outputBuffer.release();
					if (outputBuffer.streamOver && sourcePlayer == currentPlayer) {
						//from now on, we will start outputting audio from the next player (if any)
						if (nextPlayer != null)
							sourcePlayer = nextPlayer;
					}
				}

				if (framesPlayed >= framesWritten) {
					if (currentPlayer.isOutputOver()) {
						//we are done with this player!
						currentPlayer.setCurrentPosition(currentPlayer.getDuration());
						if (nextPlayer == null) {
							//there is nothing else to do!
							synchronized (openSLSync) {
								openSLRelease();
								sampleRate = 0;
							}
							outputBuffer.release();
							paused = true;
							wakeLock.release();
						} else {
							//keep playing!!!
							framesPlayed -= framesWritten;
							framesWritten = nextFramesWritten;
						}
						handler.sendMessageAtTime(Message.obtain(handler, MSG_COMPLETION, currentPlayer), SystemClock.uptimeMillis());
						currentPlayer = nextPlayer;
						if (currentPlayer != null)
							currentPlayer.startedAsNext();
						nextPlayer = null;
						nextFramesWritten = 0;
						sourcePlayer = currentPlayer;
					} else if (framesWritten != 0) {
						//underrun!!!
						currentPlayer.notifyUnderrun();
						//give the decoder some time to decode something
						try {
							Thread.sleep(30);
						} catch (Throwable ex) {
							//just ignore
						}
					}
				}
			} catch (Throwable ex) {
				try {
					outputBuffer.release();
				} catch (Throwable ex2) {
					//just ignore
				}
				if (sourcePlayer == currentPlayer) {
					synchronized (openSLSync) {
						openSLRelease();
						sampleRate = 0;
					}
					paused = true;
					playPending = false;
					currentPlayer = null;
					wakeLock.release();
				}
				handler.sendMessageAtTime(Message.obtain(handler, MSG_ERROR, new ErrorStructure(sourcePlayer, ex)), SystemClock.uptimeMillis());
				nextPlayer = null;
				nextFramesWritten = 0;
				sourcePlayer = currentPlayer;
			}
		}

		stopVisualization();

		if (wakeLock != null)
			wakeLock.release();
		synchronized (notification) {
			notification.notify();
		}
	}

	@Override
	public boolean handleMessage(Message msg) {
		if (!alive)
			return true;
		switch (msg.what) {
		case MSG_COMPLETION:
			if (msg.obj instanceof MediaCodecPlayer)
				((MediaCodecPlayer)msg.obj).onCompletion();
			break;
		case MSG_ERROR:
			if (msg.obj instanceof ErrorStructure) {
				((ErrorStructure)msg.obj).player.onError(((ErrorStructure)msg.obj).exception, 0);
				((ErrorStructure)msg.obj).exception.printStackTrace();
			}
			break;
		case MSG_SEEKCOMPLETE:
			if (msg.obj instanceof MediaCodecPlayer)
				((MediaCodecPlayer)msg.obj).onSeekComplete();
			break;
		case MSG_BUFFERINGSTART:
			if (msg.obj instanceof MediaCodecPlayer)
				((MediaCodecPlayer)msg.obj).onInfo(IMediaPlayer.INFO_BUFFERING_START, 0, null);
			break;
		case MSG_BUFFERINGEND:
			if (msg.obj instanceof MediaCodecPlayer)
				((MediaCodecPlayer)msg.obj).onInfo(IMediaPlayer.INFO_BUFFERING_END, 0, null);
			break;
		}
		return true;
	}

	public static void _initialize() {
		if (theMediaContext != null)
			return;

		theMediaContext = new MediaContext();

		alive = true;
		requestedAction = ACTION_INITIALIZE;
		playerRequestingAction = null;
		handler = new Handler(theMediaContext);

		thread = new Thread(theMediaContext, "MediaContext Output Thread");
		thread.start();

		synchronized (notification) {
			if (requestedAction == ACTION_INITIALIZE) {
				try {
					notification.wait();
				} catch (Throwable ex) {
					//just ignore
				}
			}
		}

		if (initializationError) {
			//Oops! something went wrong!
			_release();
		}
	}

	public static void _release() {
		alive = false;
		synchronized (threadNotification) {
			threadNotification.notify();
		}
		if (thread != null) {
			try {
				thread.join();
			} catch (Throwable ex) {
				//just ignore
			}
			thread = null;
		}
		synchronized (openSLSync) {
			openSLTerminate();
		}
		requestedAction = ACTION_NONE;
		handler = null;
		playerRequestingAction = null;
		theMediaContext = null;
		initializationError = false;
	}

	static boolean play(MediaCodecPlayer player) {
		if (!alive) {
			if (initializationError)
				player.mediaServerDied();
			return false;
		}
		waitToReceiveAction = true;
		synchronized (threadNotification) {
			playerRequestingAction = player;
			requestedAction = ACTION_PLAY;
			threadNotification.notify();
		}
		synchronized (notification) {
			if (playerRequestingAction != null) {
				try {
					notification.wait(PLAYER_TIMEOUT);
				} catch (Throwable ex) {
					//just ignore
				}
			}
		}
		return requestSucceeded;
	}

	static boolean pause(MediaCodecPlayer player) {
		if (!alive) {
			if (initializationError)
				player.mediaServerDied();
			return false;
		}
		waitToReceiveAction = true;
		synchronized (threadNotification) {
			playerRequestingAction = player;
			requestedAction = ACTION_PAUSE;
			threadNotification.notify();
		}
		synchronized (notification) {
			if (playerRequestingAction != null) {
				try {
					notification.wait(PLAYER_TIMEOUT);
				} catch (Throwable ex) {
					//just ignore
				}
			}
		}
		return requestSucceeded;
	}

	static boolean resume(MediaCodecPlayer player) {
		if (!alive) {
			if (initializationError)
				player.mediaServerDied();
			return false;
		}
		waitToReceiveAction = true;
		synchronized (threadNotification) {
			playerRequestingAction = player;
			requestedAction = ACTION_RESUME;
			threadNotification.notify();
		}
		synchronized (notification) {
			if (playerRequestingAction != null) {
				try {
					notification.wait(PLAYER_TIMEOUT);
				} catch (Throwable ex) {
					//just ignore
				}
			}
		}
		return requestSucceeded;
	}

	static boolean seekToAsync(MediaCodecPlayer player, int msec) {
		if (!alive) {
			if (initializationError)
				player.mediaServerDied();
			return false;
		}
		waitToReceiveAction = true;
		synchronized (threadNotification) {
			playerRequestingAction = player;
			requestedSeekMS = msec;
			requestedAction = ACTION_SEEK;
			threadNotification.notify();
		}
		synchronized (notification) {
			if (playerRequestingAction != null) {
				try {
					notification.wait(PLAYER_TIMEOUT);
				} catch (Throwable ex) {
					//just ignore
				}
			}
		}
		return requestSucceeded;
	}

	static void setNextPlayer(MediaCodecPlayer player, MediaCodecPlayer nextPlayer) {
		if (!alive)
			return;
		waitToReceiveAction = true;
		synchronized (threadNotification) {
			playerRequestingAction = player;
			nextPlayerRequested = nextPlayer;
			requestedAction = ACTION_SETNEXT;
			threadNotification.notify();
		}
		synchronized (notification) {
			if (playerRequestingAction != null) {
				try {
					notification.wait(PLAYER_TIMEOUT);
				} catch (Throwable ex) {
					//just ignore
				}
			}
		}
		nextPlayerRequested = null;
	}

	static void reset(MediaCodecPlayer player) {
		if (!alive)
			return;
		waitToReceiveAction = true;
		synchronized (threadNotification) {
			playerRequestingAction = player;
			requestedAction = ACTION_RESET;
			threadNotification.notify();
		}
		synchronized (notification) {
			if (playerRequestingAction != null) {
				try {
					notification.wait(PLAYER_TIMEOUT);
				} catch (Throwable ex) {
					//just ignore
				}
			}
		}
	}

	static void bufferingStart(MediaCodecPlayer player) {
		if (!alive || handler == null)
			return;
		handler.sendMessageAtTime(Message.obtain(handler, MSG_BUFFERINGSTART, player), SystemClock.uptimeMillis());
	}

	static void bufferingEnd(MediaCodecPlayer player) {
		if (!alive || handler == null)
			return;
		handler.sendMessageAtTime(Message.obtain(handler, MSG_BUFFERINGEND, player), SystemClock.uptimeMillis());
	}

	static void setVolumeInMillibels(int volumeInMillibels) {
		synchronized (openSLSync) {
			MediaContext.volumeInMillibels = volumeInMillibels;
			openSLSetVolumeInMillibels(volumeInMillibels);
		}
	}

	public static IMediaPlayer createMediaPlayer() {
		return new MediaCodecPlayer();
	}
}
