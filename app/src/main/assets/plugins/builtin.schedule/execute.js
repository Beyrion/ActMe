/**
 * execute.js — builtin.schedule plugin execution logic.
 *
 * All schedule business logic lives here in JS.
 * Platform integration only via generic ActMe bridge:
 *   ActMe.storage.*  — persist schedule data in plugin_items
 *   ActMe.alarm.*    — schedule notifications via AlarmManager
 */

/** Compute the next occurrence timestamp (ms) for a given time-of-day and repeat pattern. */
function nextOccurrenceMs(repeatType, timeHhMm, daysOfWeek, dayOfMonth) {
    var parts = (timeHhMm || '09:00').split(':');
    var h = parseInt(parts[0], 10) || 0;
    var m = parseInt(parts[1], 10) || 0;
    var now = Date.now();

    function todayAt(h, m) {
        var d = new Date();
        d.setHours(h, m, 0, 0);
        return d.getTime();
    }

    if (repeatType === 'DAILY') {
        var t = todayAt(h, m);
        return t > now ? t : t + 86400000;
    }

    if (repeatType === 'WEEKLY') {
        var days = daysOfWeek || [];
        if (!days.length) return nextOccurrenceMs('DAILY', timeHhMm);
        // JS getDay(): 0=Sun,1=Mon...6=Sat; our days: 1=Mon...7=Sun
        var jsDow = new Date().getDay(); // 0-6
        var ourDow = jsDow === 0 ? 7 : jsDow; // 1-7
        for (var offset = 0; offset <= 13; offset++) {
            var candidate = new Date(todayAt(h, m) + offset * 86400000);
            var cDow = candidate.getDay() === 0 ? 7 : candidate.getDay();
            if (days.indexOf(cDow) >= 0 && candidate.getTime() > now) return candidate.getTime();
        }
        return todayAt(h, m) + 86400000;
    }

    if (repeatType === 'MONTHLY') {
        var day = dayOfMonth || 1;
        var d = new Date();
        d.setDate(day); d.setHours(h, m, 0, 0);
        if (d.getTime() > now) return d.getTime();
        d.setMonth(d.getMonth() + 1);
        return d.getTime();
    }

    return now + 60000; // fallback: 1 minute from now
}

function repeatLabel(t) {
    return {NONE: '单次', DAILY: '每天', WEEKLY: '每周', MONTHLY: '每月'}[t] || t;
}

function formatReminderTime(triggerMs, repeatType) {
    var d = new Date(triggerMs);
    var time = pad(d.getHours()) + ':' + pad(d.getMinutes());
    if (repeatType === 'NONE') {
        return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + time;
    }
    return time;
}

function pad(n) { return n < 10 ? '0' + n : '' + n; }

/** Auto-increment ID stored as a special key in plugin storage. */
async function nextId() {
    var meta = await ActMe.storage.get('__meta__');
    var id = (meta && meta.nextId) ? meta.nextId : 1;
    await ActMe.storage.set('__meta__', { nextId: id + 1 });
    return id;
}

async function execute(toolName, args) {
    switch (toolName) {

        case 'create_schedule': {
            var title = (args.title || '').trim();
            if (!title) return { success: false, message: 'title 不能为空', data: {} };

            var detail      = args.detail || '';
            var repeatType  = args.repeat_type || 'NONE';
            var reminderAt  = args.reminder_at;
            // LLMs output epoch seconds (10 digits) not milliseconds (13 digits)
            if (typeof reminderAt === 'number' && reminderAt > 0 && reminderAt < 10000000000) {
                reminderAt = reminderAt * 1000;
            }
            var reminderTime = args.reminder_time || '09:00';
            var weekDays    = args.repeat_days_of_week || [];
            var monthDay    = args.repeat_day_of_month || 1;

            var triggerMs;
            if (repeatType === 'NONE') {
                triggerMs = (reminderAt && reminderAt > Date.now()) ? reminderAt : Date.now() + 60000;
            } else {
                triggerMs = nextOccurrenceMs(repeatType, reminderTime, weekDays, monthDay);
            }

            var id = await nextId();
            var key = 'schedule_' + id;
            var timeParts = reminderTime.split(':');
            var reminderTimeMinutes = parseInt(timeParts[0], 10) * 60 + parseInt(timeParts[1], 10);
            var data = { id: id, title: title, detail: detail, repeat_type: repeatType,
                         reminder_time: reminderTime,
                         reminder_time_minutes: isNaN(reminderTimeMinutes) ? 540 : reminderTimeMinutes,
                         repeat_days_of_week: weekDays,
                         repeat_day_of_month: monthDay, active: true };
            await ActMe.storage.set(key, data);

            var repeat = { type: repeatType, time: reminderTime };
            if (repeatType === 'WEEKLY')  repeat.days = weekDays;
            if (repeatType === 'MONTHLY') repeat.day  = monthDay;

            await ActMe.alarm.set(key, triggerMs, title, detail || '点击查看', repeat);

            var rl = repeatLabel(repeatType);
            var rt = formatReminderTime(triggerMs, repeatType);
            return { success: true, message: '已创建日程：' + title,
                     data: { id: id, title: title, detail: detail,
                             repeat_label: rl, reminder_time: rt } };
        }

        case 'list_schedules': {
            var items = await ActMe.storage.getAll();
            var schedules = items.filter(function(i) { return i.key.indexOf('schedule_') === 0; })
                                 .map(function(i) { return i.data; });
            return { success: true, message: '共 ' + schedules.length + ' 条日程',
                     data: { count: schedules.length, items: JSON.stringify(schedules) } };
        }

        case 'delete_schedule': {
            var id = args.id;
            var key = 'schedule_' + id;
            // Read before deleting so callers can show what was deleted
            var deleted = await ActMe.storage.get(key);
            var scheduleData = deleted ? deleted : {};
            await ActMe.storage.delete(key);
            await ActMe.alarm.cancel(key);
            var title = scheduleData.title || ('日程 ' + id);
            var detail = scheduleData.detail || '';
            var repeatType = scheduleData.repeat_type || 'NONE';
            var reminderTime = scheduleData.reminder_time || '';
            var rl = repeatLabel(repeatType);
            return { success: true, message: '已删除日程：' + title,
                     data: { id: id, title: title, detail: detail,
                             repeat_label: rl, reminder_time: reminderTime } };
        }

        default:
            return { success: false, message: '未知工具: ' + toolName, data: {} };
    }
}
