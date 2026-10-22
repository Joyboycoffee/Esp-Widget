#pragma once
#include <Arduino.h>

void uiBegin();
void uiRender();
void uiRenderHeader();
void uiClearAndRender();
void uiNextPage(int delta);
void uiApplyRotation();
void uiSplash();
void uiRebootSplash();
bool uiNeedsSecondTick();
bool uiHeaderAnimating();
