package com.nedama.psychro.client83;

import com.inductiveautomation.ignition.common.expressions.ExpressionFunctionManager;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.vision.api.client.AbstractClientModuleHook;
import com.nedama.psychro.common83.PsychroRegistration;

/** Vision client scope: client scripts and expression bindings. */
public class PsychroClientHook extends AbstractClientModuleHook {

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
