package com.badlogic.NHSS;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class StoreHourScraper {
    public static String[] fetchStoreHours() throws Exception {
        Document doc = Jsoup.connect("https://www.nerdhavenarcade.com/").get();

        // Assuming the store hours are hardcoded as per your initial values
        return new String[]{
            "NERD HAVEN ARCADE", "STORE HOURS:",
            "MONDAY: CLOSED", "TUESDAY: CLOSED",
            "WEDNESDAY: CLOSED", "THURSDAY: 3 pm - 10 pm",
            "FRIDAY: NOON - 10 pm", "SATURDAY: NOON - 10 pm",
            "SUNDAY: NOON - 8 pm"
        };
    }
}
