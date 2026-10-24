#pragma once
#include <Arduino.h>

void uiBegin();
void uiRender();
void uiRenderHeader();
void uiClearAndRender();
void uiNextPage(int delta);
void uiSetPage(int page);
int uiShownPage();  // page index currently on screen (stock page in stocks mode)
bool uiDiagActive();
void uiToggleDiag();
void uiApplyRotation();
void uiSplash();
void uiRebootSplash();
void uiSleepSplash();
bool uiNeedsSecondTick();
bool uiNeedsAnimTick();
bool uiHeaderAnimating();
