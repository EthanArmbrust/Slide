package me.ccrama.redditslide;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Environment;
import android.view.View;

import com.afollestad.materialdialogs.MaterialDialog;
import com.nostra13.universalimageloader.cache.disc.DiskCache;
import com.nostra13.universalimageloader.cache.disc.impl.UnlimitedDiskCache;
import com.nostra13.universalimageloader.cache.disc.impl.ext.LruDiskCache;
import com.nostra13.universalimageloader.cache.disc.naming.Md5FileNameGenerator;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.ccrama.redditslide.util.LogUtil;
import me.ccrama.redditslide.util.OkHttpImageDownloader;

/**
 * Created by Carlos on 4/15/2017.
 */

public class ImageFlairs {
    public static void syncFlairs(final Context context, final String subreddit) {
        new StylesheetFetchTask(subreddit, context) {
            @Override
            protected void onPostExecute(FlairStylesheet flairStylesheet) {
                super.onPostExecute(flairStylesheet);
                d.dismiss();

                flairs.edit().putBoolean(subreddit.toLowerCase(), true).commit();
            }

            Dialog d;

            @Override
            protected void onPreExecute() {
                d = new MaterialDialog.Builder(context).progress(true, 100)
                        .content(R.string.misc_please_wait)
                        .title("Syncing flairs...")
                        .cancelable(false)
                        .show();
            }
        }.execute();
    }

    static class StylesheetFetchTask extends AsyncTask<Void, Void, FlairStylesheet> {
        String subreddit;
        Context context;

        StylesheetFetchTask( String subreddit, Context context) {
            super();
            this.context = context;
            this.subreddit = subreddit;
        }

        @Override
        protected FlairStylesheet doInBackground(Void... params) {
            try {
                String stylesheet = Authentication.reddit.getStylesheet(subreddit);
                ArrayList<String> allImages = new ArrayList<>();
                FlairStylesheet flairStylesheet =  new FlairStylesheet(stylesheet);
                for (String s : flairStylesheet.getListOfFlairIds()) {
                    String classDef = flairStylesheet.getClass(flairStylesheet.stylesheetString,
                            "flair-" + s );
                    LogUtil.v("Found " + s +  " and " + classDef);
                    try {
                        String backgroundURL = flairStylesheet.getBackgroundURL(classDef);

                        if (!allImages.contains(backgroundURL)) allImages.add(backgroundURL);
                    } catch (Exception e){
                      //  e.printStackTrace();
                    }
                }
                try {
                    String cla = flairStylesheet.getClassWithParam(flairStylesheet.stylesheetString,
                            "flair", "background:url|background-image:url");
                    String total = flairStylesheet.getBackgroundURL(cla);
                    if (total != null && !total.isEmpty()) allImages.add(total);
                } catch(Exception e){
                    e.printStackTrace();
                }
                for(String backgroundURL : allImages){
                    flairStylesheet.cacheFlairsByFile(subreddit, backgroundURL, context);
                }
                return flairStylesheet;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    public static SharedPreferences flairs;

    public static boolean isSynced(String subreddit) {
        return flairs.contains(subreddit.toLowerCase());
    }

    public static class CropTransformation {
        private int width, height, x, y;
        private String id;

        public CropTransformation(Context context, String id, int width, int height, int x, int y) {
            super();
            this.id = id;
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
        }

        public Bitmap transform(Bitmap bitmap) throws Exception {
            int nX = Math.max(0, Math.min(bitmap.getWidth(), x)), nY =
                    Math.max(0, Math.min(bitmap.getHeight(), y)), nWidth =
                    Math.max(1, Math.min(bitmap.getWidth() - nX, width)), nHeight =
                    Math.max(1, Math.min(bitmap.getHeight() - nY, height));
            Bitmap b = Bitmap.createBitmap(bitmap, nX, nY, nWidth, nHeight);
            return b;
        }

    }

    static class FlairStylesheet {
        String stylesheetString;
        Dimensions defaultDimension = new Dimensions();
        Location   defaultLocation  = new Location();

        Dimensions prevDimension = null;

        class Dimensions {
            int width, height;
            Boolean missing = true;

            Dimensions(int width, int height) {
                this.width = width;
                this.height = height;
                missing = false;
            }

            Dimensions() {
            }
        }

        class Location {
            int x, y;
            Boolean missing = true;
            Boolean percent;

            Location(int x, int y, boolean percent) {
                this.x = x;
                this.y = y;
                this.percent = percent;
                missing = false;
            }

            Location() {
            }
        }

        FlairStylesheet(String stylesheetString) {
            this.stylesheetString = stylesheetString;
            Pattern linkAndFlair = Pattern.compile("\\.flair-(\\w+),.linkflair-(\\w+)");
            Matcher m = linkAndFlair.matcher(stylesheetString);
            while(m.find()){
                if(m.group(1).equals(m.group(2)) && m.end() <= stylesheetString.length()){
                    stylesheetString = stylesheetString.substring(0, m.start()) + ".flair-" + m.group(1) + stylesheetString.substring(m.end());
                }
            }
            String baseFlairDef = getClassWithParam(stylesheetString, "flair", "background:url|background-image:url");

            if (baseFlairDef == null) return;

            // Attempts to find default dimension and offset
            defaultDimension = getBackgroundSize(baseFlairDef);
            defaultLocation = getBackgroundPosition(baseFlairDef);
        }

        /**
         * Get class definition string by class name.
         *
         * @param cssDefinitionString
         * @param className
         * @return
         */
        String getClass(String cssDefinitionString, String className) {
            Pattern propertyDefinition = Pattern.compile("\\." + className + "(.*?)\\{(.+?)\\}");
            Matcher matches = propertyDefinition.matcher(cssDefinitionString);

            if (matches.find()) {
                String returning = matches.group(1);
                if(returning.startsWith(",") || returning.isEmpty()){
                    returning = matches.group(2);
                }
                return returning;
            } else {
                return null;
            }
        }

        String getClassWithParam(String cssDefinitionString, String className, String toMatch) {
            Pattern propertyDefinition = Pattern.compile("\\." + className + "\\{((.*?)("+toMatch+")(.*?))\\}");
            Matcher matches = propertyDefinition.matcher(cssDefinitionString);

            while(matches.find()){
                String second = matches.group(2);
                if (!second.contains("}")) {
                    return "background:url" + matches.group(4);
                }
            }
            return null;
        }

        /**
         * Get property value inside a class definition by property name.
         *
         * @param classDefinitionsString
         * @param property
         * @return
         */
        String getProperty(String classDefinitionsString, String property) {
            Pattern propertyDefinition = Pattern.compile(property + "\\s*:\\s*(.+?)(;|$)");
            Matcher matches = propertyDefinition.matcher(classDefinitionsString);

            if (matches.find()) {
                return matches.group(1);
            } else {
                return null;
            }
        }

        /**
         * Get flair background url in class definition.
         *
         * @param classDefinitionString
         * @return
         */
        String getBackgroundURL(String classDefinitionString) {
            return getBackgroundURL(classDefinitionString, 0);
        }

        String getBackgroundURL(String classDefinitionString, int count) {
            Pattern urlDefinition = Pattern.compile("url\\([\"\'](.+?)[\"\']\\)");
            try {
                String backgroundProperty = getProperty(classDefinitionString, "background");
                if (backgroundProperty != null) {
                    // check "background"
                    Matcher matches = urlDefinition.matcher(backgroundProperty);
                    if (matches.find()) {
                        String url = matches.group(1);
                        if (url.startsWith("//")) url = "https:" + url;
                        return url;
                    }
                }
                // either backgroundProperty is null or url cannot be found
                String backgroundImageProperty =
                        getProperty(classDefinitionString, "background-image");
                if (backgroundImageProperty != null) {
                    // check "background-image"
                    Matcher matches = urlDefinition.matcher(backgroundImageProperty);
                    if (matches.find()) {
                        String url = matches.group(1);
                        if (url.startsWith("//")) url = "https:" + url;
                        return url;
                    }
                }
            } catch(Exception ignored){

            }
            // could not find any background url
            if(count == 0){
                return getBackgroundURL(getClassWithParam(stylesheetString, "flair", "background:url|background-image:url"), 1);
            }
            return null;
        }


        /**
         * Get background dimension in class definition.
         *
         * @param classDefinitionString
         * @return
         */
        Dimensions getBackgroundSize(String classDefinitionString) {
            Pattern numberDefinition = Pattern.compile("(\\d+)\\s*px");

            // check common properties used to define width
            String widthProperty = getProperty(classDefinitionString, "width");
            if (widthProperty == null) {
                widthProperty = getProperty(classDefinitionString, "min-width");
            }
            if (widthProperty == null) {
                widthProperty = getProperty(classDefinitionString, "text-indent");
            }
            if (widthProperty == null) return new Dimensions();

            // check common properties used to define height
            String heightProperty = getProperty(classDefinitionString, "height");
            if (heightProperty == null) {
                heightProperty = getProperty(classDefinitionString, "min-height");
            }
            if (heightProperty == null) return new Dimensions();

            int width, height;
            Matcher matches;

            matches = numberDefinition.matcher(widthProperty);
            if (matches.find()) {
                width = Integer.parseInt(matches.group(1));
            } else {
                return new Dimensions();
            }

            matches = numberDefinition.matcher(heightProperty);
            if (matches.find()) {
                height = Integer.parseInt(matches.group(1));
            } else {
                return new Dimensions();
            }

            return new Dimensions(width, height);
        }

        /**
         * Get background offset in class definition.
         *
         * @param classDefinitionString
         * @return
         */
        Location getBackgroundPosition(String classDefinitionString) {
            Pattern positionDefinition = Pattern.compile("([+-]?\\d+|0)(px|%)\\s+([+-]?\\d+|0)\\s*(px|%)");

            String backgroundPositionProperty =
                    getProperty(classDefinitionString, "background-position");
            if (backgroundPositionProperty == null) return new Location();

            Matcher matches = positionDefinition.matcher((backgroundPositionProperty));
            if (matches.find()) {
                return new Location(Math.abs(Integer.parseInt(matches.group(1))),
                        Math.abs(Integer.parseInt(matches.group(3))), matches.group(2).equals("%"));
            } else {
                return new Location();
            }
        }

        /**
         * Request a flair by flair id. `.into` can be chained onto this method call.
         *
         * @param id
         * @param context
         * @return
         */
        void cacheFlairsByFile(final String sub, final String filename, final Context context) {
            final ArrayList<String> flairsToGet = new ArrayList<>();
            for (String s : getListOfFlairIds()) {
                String classDef = getClass(stylesheetString, "flair-" + s);
                String backgroundURL = getBackgroundURL(classDef);
                if (backgroundURL != null && backgroundURL.equalsIgnoreCase(filename)) {
                    flairsToGet.add(s);
                }
            }


            getFlairImageLoader(context).loadImage(filename, new ImageLoadingListener() {
                @Override
                public void onLoadingStarted(String imageUri, View view) {
                    LogUtil.v("Started loading");

                }

                @Override
                public void onLoadingFailed(String imageUri, View view, FailReason failReason) {
                    LogUtil.v("Loading failed because " + failReason.getCause().getMessage());

                }

                @Override
                public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
                    if (loadedImage != null) {
                        for (String id : flairsToGet) {
                            Bitmap newBit = null;
                            String classDef =
                                    FlairStylesheet.this.getClass(stylesheetString, "flair-" + id);
                            if (classDef == null) break;
                            Dimensions flairDimensions = getBackgroundSize(classDef);
                            if (flairDimensions.missing) flairDimensions = defaultDimension;

                            if(flairDimensions.width <= 1 && flairDimensions.height <= 1){
                                flairDimensions = getBackgroundSize(getClassWithParam(stylesheetString, "flair", "width"));
                            }

                            prevDimension = flairDimensions;

                            Location flairLocation = getBackgroundPosition(classDef);
                            if (flairLocation.missing) flairLocation = defaultLocation;

                            final Dimensions finalFlairDimensions = flairDimensions;
                            final Location finalFlairLocation = flairLocation;

                            try {
                                LogUtil.v("Numbers are: " + finalFlairDimensions.width + " " + finalFlairDimensions.height + " " + finalFlairLocation.x + " " +  finalFlairLocation.y);
                                newBit = new CropTransformation(context, id,
                                        finalFlairDimensions.width, finalFlairDimensions.height,
                                        finalFlairLocation.percent?(int)((((double)finalFlairLocation.x)/100)* finalFlairDimensions.width):finalFlairLocation.x, finalFlairLocation.percent?(int)((((double)finalFlairLocation.y)/100)* finalFlairDimensions.height):finalFlairLocation.y).transform(
                                        loadedImage);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            try {
                                getFlairImageLoader(context).getDiskCache()
                                        .save(sub.toLowerCase() + ":" + id.toLowerCase(), newBit);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        loadedImage.recycle();
                    } else {
                        LogUtil.v("Loaded image is null for " + filename);
                    }
                }

                @Override
                public void onLoadingCancelled(String imageUri, View view) {
                }
            });

        }

        /**
         * Util function
         *
         * @return
         */
        List<String> getListOfFlairIds() {
            Pattern flairId = Pattern.compile("\\.flair-(\\w+)\\s*(\\{|\\,)");
            Matcher matches = flairId.matcher(stylesheetString);

            List<String> flairIds = new ArrayList<>();
            while (matches.find()) {
                flairIds.add(matches.group(1));
            }

            Collections.sort(flairIds);
            return flairIds;
        }
    }

    public static ImageLoader getFlairImageLoader(Context context) {
        if (imageLoader == null) {
            return initFlairImageLoader(context);
        } else {
            return imageLoader;
        }
    }

    public static ImageLoader imageLoader;


    public static File getCacheDirectory(Context context) {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)
                && context.getExternalCacheDir() != null) {
            return new File(context.getExternalCacheDir(), "flairs");
        }
        return new File(context.getCacheDir(), "flairs");
    }

    public static ImageLoader initFlairImageLoader(Context context) {
        long discCacheSize = 1024 * 1024;
        DiskCache discCache;
        File dir = getCacheDirectory(context);
        int threadPoolSize;
        discCacheSize *= 100;
        threadPoolSize = 7;
        if (discCacheSize > 0) {
            try {
                dir.mkdir();
                discCache = new LruDiskCache(dir, new Md5FileNameGenerator(), discCacheSize);
            } catch (IOException e) {
                discCache = new UnlimitedDiskCache(dir);
            }
        } else {
            discCache = new UnlimitedDiskCache(dir);
        }

        options = new DisplayImageOptions.Builder().cacheOnDisk(true)
                .imageScaleType(ImageScaleType.NONE)
                .cacheInMemory(false)
                .resetViewBeforeLoading(false)
                .build();
        ImageLoaderConfiguration config =
                new ImageLoaderConfiguration.Builder(context).threadPoolSize(threadPoolSize)
                        .denyCacheImageMultipleSizesInMemory()
                        .diskCache(discCache)
                        .threadPoolSize(4)
                        .imageDownloader(new OkHttpImageDownloader(context))
                        .defaultDisplayImageOptions(options)
                        .build();

        if (ImageLoader.getInstance().isInited()) {
            ImageLoader.getInstance().destroy();
        }

        imageLoader = ImageLoader.getInstance();
        imageLoader.init(config);
        return imageLoader;

    }

    public static DisplayImageOptions options;
}