/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.model;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.SystemServicesProxy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;


/** Handle to an ActivityInfo */
class ActivityInfoHandle {
    ActivityInfo info;
}

/** A bitmap load queue */
class TaskResourceLoadQueue {
    ConcurrentLinkedQueue<Task> mQueue = new ConcurrentLinkedQueue<Task>();

    /** Adds a new task to the load queue */
    void addTasks(Collection<Task> tasks) {
        for (Task t : tasks) {
            if (!mQueue.contains(t)) {
                mQueue.add(t);
            }
        }
        synchronized(this) {
            notifyAll();
        }
    }

    /** Adds a new task to the load queue */
    void addTask(Task t) {
        if (!mQueue.contains(t)) {
            mQueue.add(t);
        }
        synchronized(this) {
            notifyAll();
        }
    }

    /**
     * Retrieves the next task from the load queue, as well as whether we want that task to be
     * force reloaded.
     */
    Task nextTask() {
        return mQueue.poll();
    }

    /** Removes a task from the load queue */
    void removeTask(Task t) {
        mQueue.remove(t);
    }

    /** Clears all the tasks from the load queue */
    void clearTasks() {
        mQueue.clear();
    }

    /** Returns whether the load queue is empty */
    boolean isEmpty() {
        return mQueue.isEmpty();
    }
}

/* Task resource loader */
class TaskResourceLoader implements Runnable {
    Context mContext;
    HandlerThread mLoadThread;
    Handler mLoadThreadHandler;
    Handler mMainThreadHandler;

    SystemServicesProxy mSystemServicesProxy;
    TaskResourceLoadQueue mLoadQueue;
    DrawableLruCache mApplicationIconCache;
    BitmapLruCache mThumbnailCache;
    Bitmap mDefaultThumbnail;
    BitmapDrawable mDefaultApplicationIcon;

    boolean mCancelled;
    boolean mWaitingOnLoadQueue;

    /** Constructor, creates a new loading thread that loads task resources in the background */
    public TaskResourceLoader(TaskResourceLoadQueue loadQueue, DrawableLruCache applicationIconCache,
                              BitmapLruCache thumbnailCache, Bitmap defaultThumbnail,
                              BitmapDrawable defaultApplicationIcon) {
        mLoadQueue = loadQueue;
        mApplicationIconCache = applicationIconCache;
        mThumbnailCache = thumbnailCache;
        mDefaultThumbnail = defaultThumbnail;
        mDefaultApplicationIcon = defaultApplicationIcon;
        mMainThreadHandler = new Handler();
        mLoadThread = new HandlerThread("Recents-TaskResourceLoader",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mLoadThread.start();
        mLoadThreadHandler = new Handler(mLoadThread.getLooper());
        mLoadThreadHandler.post(this);
    }

    /** Restarts the loader thread */
    void start(Context context) {
        mContext = context;
        mCancelled = false;
        mSystemServicesProxy = new SystemServicesProxy(context);
        // Notify the load thread to start loading
        synchronized(mLoadThread) {
            mLoadThread.notifyAll();
        }
    }

    /** Requests the loader thread to stop after the current iteration */
    void stop() {
        // Mark as cancelled for the thread to pick up
        mCancelled = true;
        mSystemServicesProxy = null;
        // If we are waiting for the load queue for more tasks, then we can just reset the
        // Context now, since nothing is using it
        if (mWaitingOnLoadQueue) {
            mContext = null;
        }
    }

    @Override
    public void run() {
        while (true) {
            if (mCancelled) {
                // We have to unset the context here, since the background thread may be using it
                // when we call stop()
                mContext = null;
                // If we are cancelled, then wait until we are started again
                synchronized(mLoadThread) {
                    try {
                        mLoadThread.wait();
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            } else {
                SystemServicesProxy ssp = mSystemServicesProxy;

                // Load the next item from the queue
                final Task t = mLoadQueue.nextTask();
                if (t != null) {
                    Drawable cachedIcon = mApplicationIconCache.get(t.key);
                    Bitmap cachedThumbnail = mThumbnailCache.get(t.key);

                    // Load the application icon if it is stale or we haven't cached one yet
                    if (cachedIcon == null) {
                        cachedIcon = getTaskDescriptionIcon(t.key, t.icon, t.iconFilename, ssp,
                                mContext.getResources());

                        if (cachedIcon == null) {
                            ActivityInfo info = ssp.getActivityInfo(t.key.baseIntent.getComponent(),
                                    t.key.userId);
                            if (info != null) {
                                cachedIcon = ssp.getActivityIcon(info, t.key.userId);
                            }
                        }

                        if (cachedIcon == null) {
                            cachedIcon = mDefaultApplicationIcon;
                        }

                        // At this point, even if we can't load the icon, we will set the default
                        // icon.
                        mApplicationIconCache.put(t.key, cachedIcon);
                    }
                    // Load the thumbnail if it is stale or we haven't cached one yet
                    if (cachedThumbnail == null) {
                        cachedThumbnail = ssp.getTaskThumbnail(t.key.id);
                        if (cachedThumbnail != null) {
                            cachedThumbnail.setHasAlpha(false);
                        } else {
                            cachedThumbnail = mDefaultThumbnail;
                        }
                        mThumbnailCache.put(t.key, cachedThumbnail);
                    }
                    if (!mCancelled) {
                        // Notify that the task data has changed
                        final Drawable newIcon = cachedIcon;
                        final Bitmap newThumbnail = cachedThumbnail == mDefaultThumbnail
                                ? null : cachedThumbnail;
                        mMainThreadHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                t.notifyTaskDataLoaded(newThumbnail, newIcon);
                            }
                        });
                    }
                }

                // If there are no other items in the list, then just wait until something is added
                if (!mCancelled && mLoadQueue.isEmpty()) {
                    synchronized(mLoadQueue) {
                        try {
                            mWaitingOnLoadQueue = true;
                            mLoadQueue.wait();
                            mWaitingOnLoadQueue = false;
                        } catch (InterruptedException ie) {
                            ie.printStackTrace();
                        }
                    }
                }
            }
        }
    }

    Drawable getTaskDescriptionIcon(Task.TaskKey taskKey, Bitmap iconBitmap, String iconFilename,
            SystemServicesProxy ssp, Resources res) {
        Bitmap tdIcon = iconBitmap != null
                ? iconBitmap
                : ActivityManager.TaskDescription.loadTaskDescriptionIcon(iconFilename);
        if (tdIcon != null) {
            return ssp.getBadgedIcon(new BitmapDrawable(res, tdIcon), taskKey.userId);
        }
        return null;
    }
}

/* Recents task loader
 * NOTE: We should not hold any references to a Context from a static instance */
public class RecentsTaskLoader {
    private static final String TAG = "RecentsTaskLoader";

    static RecentsTaskLoader sInstance;

    SystemServicesProxy mSystemServicesProxy;
    DrawableLruCache mApplicationIconCache;
    BitmapLruCache mThumbnailCache;
    StringLruCache mActivityLabelCache;
    TaskResourceLoadQueue mLoadQueue;
    TaskResourceLoader mLoader;

    RecentsPackageMonitor mPackageMonitor;

    int mMaxThumbnailCacheSize;
    int mMaxIconCacheSize;

    BitmapDrawable mDefaultApplicationIcon;
    Bitmap mDefaultThumbnail;

    /** Private Constructor */
    private RecentsTaskLoader(Context context) {
        mMaxThumbnailCacheSize = context.getResources().getInteger(
                R.integer.config_recents_max_thumbnail_count);
        mMaxIconCacheSize = context.getResources().getInteger(
                R.integer.config_recents_max_icon_count);
        int iconCacheSize = Constants.DebugFlags.App.DisableBackgroundCache ? 1 :
                mMaxIconCacheSize;
        int thumbnailCacheSize = Constants.DebugFlags.App.DisableBackgroundCache ? 1 :
                mMaxThumbnailCacheSize;

        // Create the default assets
        Bitmap icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        icon.eraseColor(0x00000000);
        mDefaultThumbnail = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        mDefaultThumbnail.setHasAlpha(false);
        mDefaultThumbnail.eraseColor(0xFFffffff);
        mDefaultApplicationIcon = new BitmapDrawable(context.getResources(), icon);

        // Initialize the proxy, cache and loaders
        mSystemServicesProxy = new SystemServicesProxy(context);
        mPackageMonitor = new RecentsPackageMonitor();
        mLoadQueue = new TaskResourceLoadQueue();
        mApplicationIconCache = new DrawableLruCache(iconCacheSize);
        mThumbnailCache = new BitmapLruCache(thumbnailCacheSize);
        mActivityLabelCache = new StringLruCache(100);
        mLoader = new TaskResourceLoader(mLoadQueue, mApplicationIconCache, mThumbnailCache,
                mDefaultThumbnail, mDefaultApplicationIcon);
    }

    /** Initializes the recents task loader */
    public static RecentsTaskLoader initialize(Context context) {
        if (sInstance == null) {
            sInstance = new RecentsTaskLoader(context);
        }
        return sInstance;
    }

    /** Returns the current recents task loader */
    public static RecentsTaskLoader getInstance() {
        return sInstance;
    }

    /** Returns the system services proxy */
    public SystemServicesProxy getSystemServicesProxy() {
        return mSystemServicesProxy;
    }

    /** Gets the list of recent tasks, ordered from back to front. */
    private static List<ActivityManager.RecentTaskInfo> getRecentTasks(SystemServicesProxy ssp,
            boolean isTopTaskHome) {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        List<ActivityManager.RecentTaskInfo> tasks =
                ssp.getRecentTasks(config.maxNumTasksToLoad, UserHandle.CURRENT.getIdentifier(),
                        isTopTaskHome);
        Collections.reverse(tasks);
        return tasks;
    }

    /** Returns the activity icon using as many cached values as we can. */
    public Drawable getAndUpdateActivityIcon(Task.TaskKey taskKey,
             ActivityManager.TaskDescription td, SystemServicesProxy ssp,
             Resources res, ActivityInfoHandle infoHandle, boolean preloadTask) {
        // Return the cached activity icon if it exists
        Drawable icon = mApplicationIconCache.getAndInvalidateIfModified(taskKey);
        if (icon != null) {
            return icon;
        }

        // If we are preloading this task, continue to load the task description icon or the
        // activity icon
        if (preloadTask) {

            // Return and cache the task description icon if it exists
            Drawable tdDrawable = mLoader.getTaskDescriptionIcon(taskKey, td.getInMemoryIcon(),
                    td.getIconFilename(), ssp, res);
            if (tdDrawable != null) {
                mApplicationIconCache.put(taskKey, tdDrawable);
                return tdDrawable;
            }

            // Load the icon from the activity info and cache it
            if (infoHandle.info == null) {
                infoHandle.info = ssp.getActivityInfo(taskKey.baseIntent.getComponent(),
                        taskKey.userId);
            }
            if (infoHandle.info != null) {
                icon = ssp.getActivityIcon(infoHandle.info, taskKey.userId);
                if (icon != null) {
                    mApplicationIconCache.put(taskKey, icon);
                    return icon;
                }
            }
        }
        // If we couldn't load any icon, return null
        return null;
    }

    /** Returns the activity label using as many cached values as we can. */
    public String getAndUpdateActivityLabel(Task.TaskKey taskKey,
            ActivityManager.TaskDescription td, SystemServicesProxy ssp,
            ActivityInfoHandle infoHandle) {
        // Return the task description label if it exists
        if (td != null && td.getLabel() != null) {
            return td.getLabel();
        }
        // Return the cached activity label if it exists
        String label = mActivityLabelCache.getAndInvalidateIfModified(taskKey);
        if (label != null) {
            return label;
        }
        // All short paths failed, load the label from the activity info and cache it
        if (infoHandle.info == null) {
            infoHandle.info = ssp.getActivityInfo(taskKey.baseIntent.getComponent(),
                    taskKey.userId);
        }
        if (infoHandle.info != null) {
            label = ssp.getActivityLabel(infoHandle.info);
            mActivityLabelCache.put(taskKey, label);
        } else {
            Log.w(TAG, "Missing ActivityInfo for " + taskKey.baseIntent.getComponent()
                    + " u=" + taskKey.userId);
        }
        return label;
    }

    /** Returns the activity's primary color. */
    public int getActivityPrimaryColor(ActivityManager.TaskDescription td,
            RecentsConfiguration config) {
        if (td != null && td.getPrimaryColor() != 0) {
            return td.getPrimaryColor();
        }
        return config.taskBarViewDefaultBackgroundColor;
    }

    /** Reload the set of recent tasks */
    public SpaceNode reload(Context context, int preloadCount, boolean isTopTaskHome) {
        ArrayList<Task.TaskKey> taskKeys = new ArrayList<Task.TaskKey>();
        ArrayList<Task> tasksToLoad = new ArrayList<Task>();
        TaskStack stack = getTaskStack(mSystemServicesProxy, context.getResources(),
                -1, preloadCount, true, isTopTaskHome, taskKeys, tasksToLoad);
        SpaceNode root = new SpaceNode();
        root.setStack(stack);

        // Start the task loader and add all the tasks we need to load
        mLoader.start(context);
        mLoadQueue.addTasks(tasksToLoad);

        // Update the package monitor with the list of packages to listen for
        mPackageMonitor.setTasks(taskKeys);

        return root;
    }

    /** Creates a lightweight stack of the current recent tasks, without thumbnails and icons. */
    public TaskStack getTaskStack(SystemServicesProxy ssp, Resources res,
            int preloadTaskId, int preloadTaskCount,
            boolean loadTaskThumbnails, boolean isTopTaskHome,
            List<Task.TaskKey> taskKeysOut, List<Task> tasksToLoadOut) {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        List<ActivityManager.RecentTaskInfo> tasks = getRecentTasks(ssp, isTopTaskHome);
        HashMap<Task.ComponentNameKey, ActivityInfoHandle> activityInfoCache =
                new HashMap<Task.ComponentNameKey, ActivityInfoHandle>();
        ArrayList<Task> tasksToAdd = new ArrayList<Task>();
        TaskStack stack = new TaskStack();

        int taskCount = tasks.size();
        for (int i = 0; i < taskCount; i++) {
            ActivityManager.RecentTaskInfo t = tasks.get(i);

            // Compose the task key
            Task.TaskKey taskKey = new Task.TaskKey(t.persistentId, t.baseIntent, t.userId,
                    t.firstActiveTime, t.lastActiveTime);

            // Get an existing activity info handle if possible
            Task.ComponentNameKey cnKey = taskKey.getComponentNameKey();
            ActivityInfoHandle infoHandle;
            boolean hasCachedActivityInfo = false;
            if (activityInfoCache.containsKey(cnKey)) {
                infoHandle = activityInfoCache.get(cnKey);
                hasCachedActivityInfo = true;
            } else {
                infoHandle = new ActivityInfoHandle();
            }

            // Determine whether to preload this task
            boolean preloadTask = false;
            if (preloadTaskId > 0) {
                preloadTask = (t.id == preloadTaskId);
            } else if (preloadTaskCount > 0) {
                preloadTask = (i >= (taskCount - preloadTaskCount));
            }

            // Load the label, icon, and color
            String activityLabel  = getAndUpdateActivityLabel(taskKey, t.taskDescription,
                    ssp, infoHandle);
            Drawable activityIcon = getAndUpdateActivityIcon(taskKey, t.taskDescription,
                    ssp, res, infoHandle, preloadTask);
            int activityColor = getActivityPrimaryColor(t.taskDescription, config);

            // Update the activity info cache
            if (!hasCachedActivityInfo && infoHandle.info != null) {
                activityInfoCache.put(cnKey, infoHandle);
            }

            Bitmap icon = t.taskDescription != null
                    ? t.taskDescription.getInMemoryIcon()
                    : null;
            String iconFilename = t.taskDescription != null
                    ? t.taskDescription.getIconFilename()
                    : null;

            // Add the task to the stack
            Task task = new Task(taskKey, (t.id > -1), t.affiliatedTaskId, t.affiliatedTaskColor,
                    activityLabel, activityIcon, activityColor, (i == (taskCount - 1)),
                    config.lockToAppEnabled, icon, iconFilename);

            if (preloadTask && loadTaskThumbnails) {
                // Load the thumbnail from the cache if possible
                task.thumbnail = mThumbnailCache.getAndInvalidateIfModified(taskKey);
                if (task.thumbnail == null) {
                    // Load the thumbnail from the system
                    task.thumbnail = ssp.getTaskThumbnail(taskKey.id);
                    if (task.thumbnail != null) {
                        task.thumbnail.setHasAlpha(false);
                        mThumbnailCache.put(taskKey, task.thumbnail);
                    }
                }
                if (task.thumbnail == null && tasksToLoadOut != null) {
                    // Either the task has changed since the last active time, or it was not
                    // previously cached, so try and load the task anew.
                    tasksToLoadOut.add(task);
                }
            }

            // Add to the list of task keys
            if (taskKeysOut != null) {
                taskKeysOut.add(taskKey);
            }
            // Add the task to the stack
            tasksToAdd.add(task);
        }
        stack.setTasks(tasksToAdd);
        stack.createAffiliatedGroupings(config);
        return stack;
    }

    /** Acquires the task resource data directly from the pool. */
    public void loadTaskData(Task t) {
        Drawable applicationIcon = mApplicationIconCache.getAndInvalidateIfModified(t.key);
        Bitmap thumbnail = mThumbnailCache.getAndInvalidateIfModified(t.key);

        // Grab the thumbnail/icon from the cache, if either don't exist, then trigger a reload and
        // use the default assets in their place until they load
        boolean requiresLoad = (applicationIcon == null) || (thumbnail == null);
        applicationIcon = applicationIcon != null ? applicationIcon : mDefaultApplicationIcon;
        if (requiresLoad) {
            mLoadQueue.addTask(t);
        }
        t.notifyTaskDataLoaded(thumbnail == mDefaultThumbnail ? null : thumbnail, applicationIcon);
    }

    /** Releases the task resource data back into the pool. */
    public void unloadTaskData(Task t) {
        mLoadQueue.removeTask(t);
        t.notifyTaskDataUnloaded(null, mDefaultApplicationIcon);
    }

    /** Completely removes the resource data from the pool. */
    public void deleteTaskData(Task t, boolean notifyTaskDataUnloaded) {
        mLoadQueue.removeTask(t);
        mThumbnailCache.remove(t.key);
        mApplicationIconCache.remove(t.key);
        if (notifyTaskDataUnloaded) {
            t.notifyTaskDataUnloaded(null, mDefaultApplicationIcon);
        }
    }

    /** Stops the task loader and clears all pending tasks */
    void stopLoader() {
        mLoader.stop();
        mLoadQueue.clearTasks();
    }

    /** Registers any broadcast receivers. */
    public void registerReceivers(Context context, RecentsPackageMonitor.PackageCallbacks cb) {
        // Register the broadcast receiver to handle messages related to packages being added/removed
        mPackageMonitor.register(context, cb);
    }

    /** Unregisters any broadcast receivers. */
    public void unregisterReceivers() {
        mPackageMonitor.unregister();
    }

    /**
     * Handles signals from the system, trimming memory when requested to prevent us from running
     * out of memory.
     */
    public void onTrimMemory(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                // Stop the loader immediately when the UI is no longer visible
                stopLoader();
                mThumbnailCache.trimToSize(Math.max(
                        Constants.Values.RecentsTaskLoader.PreloadFirstTasksCount,
                        mMaxThumbnailCacheSize / 2));
                mApplicationIconCache.trimToSize(Math.max(
                        Constants.Values.RecentsTaskLoader.PreloadFirstTasksCount,
                        mMaxIconCacheSize / 2));
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                // We are leaving recents, so trim the data a bit
                mThumbnailCache.trimToSize(mMaxThumbnailCacheSize / 2);
                mApplicationIconCache.trimToSize(mMaxIconCacheSize / 2);
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                // We are going to be low on memory
                mThumbnailCache.trimToSize(mMaxThumbnailCacheSize / 4);
                mApplicationIconCache.trimToSize(mMaxIconCacheSize / 4);
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // We are low on memory, so release everything
                mThumbnailCache.evictAll();
                mApplicationIconCache.evictAll();
                // The cache is small, only clear the label cache when we are critical
                mActivityLabelCache.evictAll();
                break;
            default:
                break;
        }
    }
}
