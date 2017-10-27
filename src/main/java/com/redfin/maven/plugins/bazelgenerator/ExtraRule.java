package com.redfin.maven.plugins.bazelgenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExtraRule {
    private String name;
    private String rule;
    private boolean dependency;

    public String getName() {
        return name;
    }
}
