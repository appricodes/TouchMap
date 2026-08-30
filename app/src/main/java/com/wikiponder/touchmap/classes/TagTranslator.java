package com.wikiponder.touchmap.classes;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class TagTranslator {
	private static final String TAG = "TagCacheManager";
	
	private Map<String, String> tagList;
	
	public TagTranslator(Context context, String langCode) {
		
		// load dictionary
		tagList = new HashMap<>();
		try {
			InputStream is = context.getAssets().open("tags_dicts/" + langCode + ".txt");
			int size = is.available();
			byte[] buffer = new byte[size];
			is.read(buffer);
			is.close();
			
			JSONObject json = new JSONObject(new String(buffer, StandardCharsets.UTF_8));
			// add them to tagList
			Iterator<String> keys = json.keys();
			while (keys.hasNext()) {
				String key = keys.next();
				String value = json.getString(key);
				tagList.put(key, value);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}

	public String translate(String key) {
		return tagList.getOrDefault(key, key);
	}
}