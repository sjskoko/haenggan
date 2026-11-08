import { z } from "zod";
export const dateKey = (date = new Date()) => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).format(date);
export function validDate(value: string) { return /^\d{4}-\d{2}-\d{2}$/.test(value) && !isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0,10) === value; }
export function addDays(value: string, days: number) { const d = new Date(`${value}T12:00:00Z`); d.setUTCDate(d.getUTCDate()+days); return d.toISOString().slice(0,10); }
export function weekStart(value: string) { const day = new Date(`${value}T12:00:00Z`).getUTCDay(); return addDays(value,-((day+6)%7)); }
export function koreanDate(value: string, short=false) { return new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", ...(short?{}:{weekday:"long" as const}), timeZone:"Asia/Seoul" }).format(new Date(`${value}T12:00:00+09:00`)); }
export const entryInput = z.object({ id:z.string().uuid(), date:z.string().refine(validDate), title:z.string().trim().max(80).default(""), transcript:z.string().trim().max(16000), mode:z.enum(["listen","question"]).default("listen"), mood:z.enum(["편안","기쁨","복잡","지침","설렘"]).nullable().default(null), duration:z.number().int().min(0).max(1200).default(0), keepAudio:z.boolean().default(false) });
export type Entry = {id:string;date:string;title:string;transcript:string;summary:string;summaryOrigin:string;mode:string;mood:string|null;hasAudio:boolean;duration:number;revision:number;createdAt:string;updatedAt:string};
export type Report = {title:string; sections:{heading:string;body:string;evidence:{id:string;quote:string}[]}[];nextStep:string};
export function sourceSignature(rows: {id:string;revision:number}[]) { return rows.map(e=>`${e.id}:${e.revision}`).sort().join("|"); }
export function validateEvidence(report:Report, rows:Entry[]) { const normalize=(s:string)=>s.replace(/\s+/g," ").trim(); for(const section of report.sections) { section.evidence=section.evidence.filter(e=>{const entry=rows.find(x=>x.id===e.id);return entry&&e.quote.trim().length>0&&normalize(entry.transcript+"\n"+entry.summary).includes(normalize(e.quote));}); }return report; }
export const titleFromText = (text:string) => text.replace(/\s+/g," ").trim().slice(0,36) || "목소리로 남긴 하루";
