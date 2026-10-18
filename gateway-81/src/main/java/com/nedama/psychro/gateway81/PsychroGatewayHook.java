package com.nedama.psychro.gateway81;

import com.inductiveautomation.ignition.common.expressions.ExpressionFunctionManager;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.nedama.psychro.common81.PsychroRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Gateway scope: gateway scripts, Perspective scripts and bindings, and expression tags. */
public class PsychroGatewayHook extends AbstractGatewayModuleHook {

    private static final Logger LOG = LoggerFactory.getLogger("Psychrometrics");

    @Override
    public void setup(GatewayContext context) {
        // Nothing to set up: the module only adds functions.
    }

    @Override
    public void startup(LicenseState activationState) {
        LOG.info("Psychrometrics started: system.psychro scripts and psychro* expression functions are available.");
    }

    @Override
    public void shutdown() {
        // Nothing to release.
    }

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

    @Override
    public boolean isFreeModule() {
        return true;
    }

    @Override
    public boolean isMakerEditionCompatible() {
        return true;
    }
}
