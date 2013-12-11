package com.example.ffmpegtest;

import android.content.Context;
import android.content.Intent;

public class Util {
	
	public static Intent createShareChooserIntentWithTitleAndUrl(Context c, String title, String url){
        return Intent.createChooser(createShareIntentWithUrl(c, url), title);
	}
	
	public static Intent createShareIntentWithUrl(Context c, String url) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, c.getString(R.string.share_subject));
        shareIntent.putExtra(Intent.EXTRA_TEXT, url.replace("%2F", "/"));	// TODO: Fix this in S3 library
        return shareIntent;
    } 

}