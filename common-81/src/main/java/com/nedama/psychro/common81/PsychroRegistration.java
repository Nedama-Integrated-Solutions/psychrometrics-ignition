package com.nedama.psychro.common81;

import com.inductiveautomation.ignition.common.expressions.ExpressionFunctionManager;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;

/** Registers the script module and expression functions. Every scope's hook calls these two methods. */
public final class PsychroRegistration {

    /** Scripting namespace. */
    public static final String SCRIPT_PATH = "system.psychro";
    /** Expression function category shown in the designer. */
    public static final String EXPRESSION_CATEGORY = "Psychrometrics";

    private PsychroRegistration() {
    }

    public static void registerScripts(ScriptManager manager) {
        manager.addScriptModule(SCRIPT_PATH, new PsychroScriptModule(), new PropertiesFileDocProvider());
    }

    public static void registerExpressions(ExpressionFunctionManager factory) {
        factory.getCategories().add(EXPRESSION_CATEGORY);
        for (PsychroFunction function : PsychroFunction.all()) {
            factory.addFunction(function.getName(), EXPRESSION_CATEGORY, function);
        }
    }
}
