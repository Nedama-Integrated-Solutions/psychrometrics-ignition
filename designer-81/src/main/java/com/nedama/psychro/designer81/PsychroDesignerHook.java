package com.nedama.psychro.designer81;

import com.inductiveautomation.ignition.common.expressions.ExpressionFunctionManager;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.nedama.psychro.common81.PsychroRegistration;

/** Designer scope: script console, bindings and autocomplete hints. */
public class PsychroDesignerHook extends AbstractDesignerModuleHook {

    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);
        PsychroRegistration.registerScripts(manager);
    }

    @Override
    public void configureFunctionFactory(ExpressionFunctionManager factory) {
        PsychroRegistration.registerExpressions(factory);
        super.configureFunctionFactory(factory);
    }
}
