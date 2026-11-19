package com.kitkatvisuals.event;

/** Запрос показать уведомление. rgb без альфы. */
public record NotificationEvent(String title, String text, int rgb) {}
