#pragma once
#include <Arduino.h>

void uiBegin();
void uiRender();
void uiRenderHeader();
void uiClearAndRender();
void uiNextPage(int delta);
bool uiDiagActive();
void uiToggleDiag();
void uiApplyRotation();
void uiSplash();
void uiRebootSplash();
void uiSleepSplash();
bool uiNeedsSecondTick();
bool uiHeaderAnimating();
