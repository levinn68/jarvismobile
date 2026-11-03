const SKILLS=new Map();
export function registerSkill(skill){if(!skill?.id||typeof skill.run!=="function")throw new Error("Skill requires id and run()");SKILLS.set(skill.id,skill);return skill.id;}
export function getSkill(id){return SKILLS.get(id)||null;}
export function listSkills(){return [...SKILLS.values()].map(({id,name,description,capabilities=[]})=>({id,name,description,capabilities}));}
export async function runSkill(id,input,context){const skill=getSkill(id);if(!skill)throw new Error("Unknown skill: "+id);return skill.run(input,context);}
