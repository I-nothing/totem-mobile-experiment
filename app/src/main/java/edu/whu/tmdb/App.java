package edu.whu.tmdb;

import android.app.Application;
import android.content.Context;

public class App extends Application {

    // 全局静态 Context
    public static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化全局 context
        context = getApplicationContext();
    }
}
