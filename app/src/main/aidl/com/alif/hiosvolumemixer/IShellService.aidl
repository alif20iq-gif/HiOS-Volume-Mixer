package com.alif.hiosvolumemixer;

interface IShellService {
    String execute(String command);
    String getActivePackages();
    String setPackageVolume(String packageName, float volume);
}