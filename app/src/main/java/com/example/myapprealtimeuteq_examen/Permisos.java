package com.example.myapprealtimeuteq_examen;

import android.content.pm.PackageManager;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class Permisos {
    private AppCompatActivity activity;

    public Permisos(AppCompatActivity activity) {
        this.activity = activity;
    }

    public ArrayList<String> getPermisosNoAprobados(ArrayList<String>  listaPermisos) {
        ArrayList<String> list = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 23)
            for(String permiso: listaPermisos) {
                if (activity.checkSelfPermission(permiso) != PackageManager.PERMISSION_GRANTED) {
                    list.add(permiso);
                }
            }
        return list;
    }
}
