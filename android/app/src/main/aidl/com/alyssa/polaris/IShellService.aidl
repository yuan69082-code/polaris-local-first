package com.alyssa.polaris;

interface IShellService {
    String[] exec(String command);
    int uid();
    void destroy() = 16777114;
}
