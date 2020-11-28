package com.sergkit.co2meter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class DataTransfer extends Object{
    public String dt;
    public Float t;
    public Float h;
    public Float co2;
    public String s_t;
    public String s_h;
    public String s_co2;

    private SimpleDateFormat formatterFrom;
    private SimpleDateFormat formatterTo;
    private Locale LocaleRu = new Locale("ru","RU");

    public DataTransfer(){
        formatterFrom = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        formatterFrom.setTimeZone(TimeZone.getTimeZone("GMT"));
        formatterTo = new SimpleDateFormat("HH:mm:ss dd-MM-yyyy");
        formatterTo.setTimeZone(TimeZone.getTimeZone("GMT+3:00"));
    }


    public String setDt(String dt) {
        try {
            Date date = formatterFrom.parse(dt);
            this.dt =formatterTo.format(date);
        } catch (Exception e) {
            this.dt = "----";
        }
        return this.dt;

    }

    public String setT(float t){
        this.t=t;
        this.s_t=String.format(LocaleRu, "%.1f °C", t);
        return s_t;
    }
    public String setH(float t){
        this.h=t;
        this.s_h=String.format(LocaleRu, "%.1f %%", t);
        return s_h;
    }
    public String setCo2(float t){
        this.co2=t;
        this.s_co2=String.format(LocaleRu, "%.0f ppm",t);
        return s_co2;
    }
}


